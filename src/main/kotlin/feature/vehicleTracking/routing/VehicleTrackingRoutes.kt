package com.mozgobolt.feature.vehicleTracking.routing

import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RECONNECT_WARNING_LEAD_TIME
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.SSE_EVENT_RECONNECT
import com.mozgobolt.core.modules.plugin.SSE_HEARTBEAT_PERIOD
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.core.utility.functions.remainingJwtValidityOrNull
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.routing.requireRole
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
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
) {
    protectedApi {
        validatedPost<TelemetryBatchRequestDto>(
            "/vehicle-tracking/telemetry",
            BodyLimit.MEDIUM,
            RequestTimeout.STANDARD,
        ) { request ->
            if (!requireRole(UserRole.VENDOR)) return@validatedPost
            val userId = call.currentUserIdOrNull() ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

            val points =
                request.points.map { point ->
                    TelemetryPoint(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        recordedAt = Instant.parse(point.recordedAt),
                    )
                }

            vehicleTrackingService.ingestTelemetry(userId, points).fold(
                onSuccess = { call.respond(HttpStatusCode.Accepted) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }

        sse(VEHICLE_TRACKING_LIVE_PATH) {
            call.currentUserIdOrNull() ?: return@sse
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

            val (snapshotUpdates, liveUpdates) =
                when (liveRequest) {
                    is LiveRequest.All -> hub.snapshotAll() to hub.subscribeAll()

                    is LiveRequest.Nearby ->
                        hub.snapshotNear(liveRequest.latitude, liveRequest.longitude, liveRequest.radiusKm) to
                            hub.subscribeNear(liveRequest.latitude, liveRequest.longitude, liveRequest.radiusKm)

                    is LiveRequest.Invalid -> {
                        val error = ErrorResponse(error = ERROR_CODE_INVALID_REQUEST, message = liveRequest.reason)
                        send(ServerSentEvent(event = SSE_EVENT_ERROR, data = Json.encodeToString(error)))
                        return@sse
                    }
                }

            heartbeat { period = SSE_HEARTBEAT_PERIOD }

            snapshotUpdates.forEach { update -> sendLocation(update) }

            launch {
                delay((remainingTokenValidity - RECONNECT_WARNING_LEAD_TIME).coerceAtLeast(Duration.ZERO))
                send(ServerSentEvent(event = SSE_EVENT_RECONNECT))
            }

            withTimeoutOrNull(remainingTokenValidity) {
                liveUpdates.collect { event ->
                    when (event) {
                        is VehicleLiveEvent.Position -> sendLocation(event.update)
                        is VehicleLiveEvent.Offline -> sendOffline(event.vehicleId)
                    }
                }
            }
        }
    }
}

private fun TelemetryError.toHttpStatusCode() =
    when (this) {
        TelemetryError.NOT_LINKED_TO_VEHICLE -> HttpStatusCode.Conflict
    }
