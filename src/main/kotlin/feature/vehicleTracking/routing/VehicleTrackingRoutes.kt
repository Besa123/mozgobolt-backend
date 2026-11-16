/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.routing

import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.data.idempotency.IdempotentResult
import com.mozgobolt.core.data.idempotency.idempotent
import com.mozgobolt.core.data.idempotency.idempotentResult
import com.mozgobolt.core.domain.security.AccessTokenSessionValidator
import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.SSE_EVENT_RECONNECT
import com.mozgobolt.core.modules.plugin.SSE_HEARTBEAT_PERIOD
import com.mozgobolt.core.modules.plugin.runSseCollectionWithReconnectWarning
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.collectWhileAccessSessionActive
import com.mozgobolt.core.utility.functions.currentTokenFamilyIdOrNull
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.core.utility.functions.remainingJwtValidityOrNull
import com.mozgobolt.core.utility.functions.respondUnauthorized
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.routing.requireRole
import com.mozgobolt.feature.vehicleTracking.domain.NearbyLocationFeed
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.routing.dto.request.TelemetryBatchRequestDto
import com.mozgobolt.feature.vehicleTracking.routing.dto.response.VehicleOfflineResponseDto
import com.mozgobolt.feature.vehicleTracking.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.time.Duration

const val VEHICLE_TRACKING_LIVE_PATH = "/vehicle-tracking/live"

private const val SSE_EVENT_LOCATION = "location"
private const val SSE_EVENT_OFFLINE = "offline"
private const val SSE_EVENT_ERROR = "error"

private const val QUERY_PARAM_SCOPE = "scope"
private const val QUERY_PARAM_LAT = "lat"
private const val QUERY_PARAM_LON = "lon"
private const val QUERY_PARAM_RADIUS_KM = "radiusKm"

private const val ERROR_CODE_INVALID_REQUEST = "INVALID_REQUEST"

fun Route.vehicleTrackingRoutes(
    vehicleTrackingService: VehicleTrackingService,
    hub: VehicleLocationHub,
    accessTokenSessionValidator: AccessTokenSessionValidator,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi {
        vehicleTrackingTelemetryRoute(vehicleTrackingService, idempotencyStore)
        vehicleTrackingLiveRoute(hub, accessTokenSessionValidator)
    }
}

private fun Route.vehicleTrackingTelemetryRoute(
    vehicleTrackingService: VehicleTrackingService,
    idempotencyStore: IdempotencyStore,
) {
    validatedPost<TelemetryBatchRequestDto>(
        "/vehicle-tracking/telemetry",
        BodyLimit.MEDIUM,
        RequestTimeout.STANDARD,
    ) { request ->
        if (!requireRole(UserRole.VENDOR)) return@validatedPost
        val userId = call.currentUserIdOrNull() ?: return@validatedPost call.respondUnauthorized()

        val points =
            request.points.map { point ->
                TelemetryPoint(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    recordedAt = Instant.parse(point.recordedAt),
                )
            }

        idempotent(
            idempotencyStore,
            requestFingerprint = "$userId:${Json.encodeToString(request)}",
        ) {
            vehicleTrackingService.prepareTelemetry(userId, points).fold(
                onSuccess = { plan ->
                    IdempotentResult(
                        statusCode = HttpStatusCode.Accepted,
                        body = "",
                        afterCommitBestEffort = { vehicleTrackingService.publishTelemetry(plan) },
                    )
                },
                onError = { error ->
                    idempotentResult(
                        statusCode = error.toHttpStatusCode(),
                        body = ErrorResponse(error = error.name),
                        shouldCacheResponse = false,
                    )
                },
            )
        }
    }
}

@Suppress("DEPRECATION") // Reject before the SSE handler commits a 200 response.
private fun Route.vehicleTrackingLiveRoute(
    hub: VehicleLocationHub,
    accessTokenSessionValidator: AccessTokenSessionValidator,
) {
    route(VEHICLE_TRACKING_LIVE_PATH) {
        intercept(ApplicationCallPipeline.Call) {
            if (!context.requireRole(UserRole.BUYER)) finish()
        }

        sse {
            val userId = call.currentUserIdOrNull() ?: return@sse
            val familyId = call.currentTokenFamilyIdOrNull() ?: return@sse
            val remainingTokenValidity = call.remainingJwtValidityOrNull() ?: return@sse
            if (remainingTokenValidity <= Duration.ZERO) return@sse

            suspend fun sendLocation(update: VehicleLocationUpdate) {
                send(ServerSentEvent(data = Json.encodeToString(update.toResponseDto()), event = SSE_EVENT_LOCATION))
            }

            suspend fun sendOffline(vehicleId: Int) {
                val offline = VehicleOfflineResponseDto(vehicleId)
                send(ServerSentEvent(data = Json.encodeToString(offline), event = SSE_EVENT_OFFLINE))
            }

            val liveRequest =
                parseLiveRequest(
                    scope = call.request.queryParameters[QUERY_PARAM_SCOPE],
                    lat = call.request.queryParameters[QUERY_PARAM_LAT],
                    lon = call.request.queryParameters[QUERY_PARAM_LON],
                    radiusKm = call.request.queryParameters[QUERY_PARAM_RADIUS_KM],
                )
            if (liveRequest is LiveRequest.Invalid) {
                val error = ErrorResponse(error = ERROR_CODE_INVALID_REQUEST, message = liveRequest.reason)
                send(ServerSentEvent(event = SSE_EVENT_ERROR, data = Json.encodeToString(error)))
                return@sse
            }
            val nearbyRequest = liveRequest as LiveRequest.Nearby

            val feed = hub.openNearby(nearbyRequest.latitude, nearbyRequest.longitude, nearbyRequest.radiusKm)
            try {
                heartbeat { period = SSE_HEARTBEAT_PERIOD }
                feed.snapshot.forEach { update -> sendLocation(update) }

                collectVehicleTrackingLiveEvents(
                    feed = feed,
                    session = AccessTokenSession(userId, familyId),
                    remainingTokenValidity = remainingTokenValidity,
                    accessTokenSessionValidator = accessTokenSessionValidator,
                    sink =
                        LiveEventSink(
                            sendReconnectEvent = { send(ServerSentEvent(event = SSE_EVENT_RECONNECT)) },
                            sendLocation = ::sendLocation,
                            sendOffline = ::sendOffline,
                        ),
                )
            } finally {
                feed.close()
            }
        }
    }
}

/** Which user and JWT session family a live connection belongs to, for the revocation recheck. */
private data class AccessTokenSession(
    val userId: Int,
    val familyId: String,
)

/** The three ways this route's collection loop talks back to the client, bundled to keep the
 * surrounding function's parameter list from growing with every event kind this route emits. */
private data class LiveEventSink(
    val sendReconnectEvent: suspend () -> Unit,
    val sendLocation: suspend (VehicleLocationUpdate) -> Unit,
    val sendOffline: suspend (Int) -> Unit,
)

private suspend fun collectVehicleTrackingLiveEvents(
    feed: NearbyLocationFeed,
    session: AccessTokenSession,
    remainingTokenValidity: Duration,
    accessTokenSessionValidator: AccessTokenSessionValidator,
    sink: LiveEventSink,
) {
    withTimeoutOrNull(remainingTokenValidity) {
        runSseCollectionWithReconnectWarning(remainingTokenValidity, sink.sendReconnectEvent) {
            feed.events.collectWhileAccessSessionActive(
                userId = session.userId,
                familyId = session.familyId,
                validator = accessTokenSessionValidator,
            ) { event ->
                when (event) {
                    is VehicleLiveEvent.Position -> sink.sendLocation(event.update)
                    is VehicleLiveEvent.Offline -> sink.sendOffline(event.vehicleId)
                }
            }
        }
    }
}

private fun TelemetryError.toHttpStatusCode() =
    when (this) {
        TelemetryError.NOT_LINKED_TO_VEHICLE -> HttpStatusCode.Conflict
    }
