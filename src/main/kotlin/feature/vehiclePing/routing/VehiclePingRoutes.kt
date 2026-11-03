/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.routing

import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.data.idempotency.idempotent
import com.mozgobolt.core.data.idempotency.idempotentResult
import com.mozgobolt.core.domain.security.AccessTokenSessionValidator
import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RECONNECT_WARNING_LEAD_TIME
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.SSE_EVENT_RECONNECT
import com.mozgobolt.core.modules.plugin.SSE_HEARTBEAT_PERIOD
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.collectWhileAccessSessionActive
import com.mozgobolt.core.utility.functions.currentTokenFamilyIdOrNull
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.core.utility.functions.remainingJwtValidityOrNull
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.routing.requireRole
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.routing.dto.request.PingVehicleRequestDto
import com.mozgobolt.feature.vehiclePing.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration

const val VEHICLE_PING_LIVE_PATH = "/vehicle-tracking/pings/live"

private const val INVALID_VEHICLE_ID = "INVALID_VEHICLE_ID"
private const val SSE_EVENT_PING = "ping"

fun Route.vehiclePingRoutes(
    vehiclePingService: VehiclePingService,
    hub: VehiclePingHub,
    idempotencyStore: IdempotencyStore,
    accessTokenSessionValidator: AccessTokenSessionValidator,
) {
    protectedApi {
        // A body was added so a buyer can tell the driver roughly where they are — see
        // PingVehicleRequestDto and VehiclePingServiceI for the privacy coarsening applied to it
        // immediately on receipt.
        validatedPost<PingVehicleRequestDto>(
            "/vehicles/{id}/ping",
            BodyLimit.TINY,
            RequestTimeout.STANDARD,
        ) { request ->
            if (!requireRole(UserRole.BUYER)) return@validatedPost
            val buyerUserId =
                call.currentUserIdOrNull() ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)
            val vehicleId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@validatedPost call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = INVALID_VEHICLE_ID),
                    )

            idempotent(
                idempotencyStore,
                requestFingerprint = "vehicle-ping:$buyerUserId:$vehicleId:${Json.encodeToString(request)}",
            ) {
                val pingResult =
                    vehiclePingService.ping(
                        buyerUserId = buyerUserId,
                        vehicleId = vehicleId,
                        latitude = request.latitude,
                        longitude = request.longitude,
                    )
                pingResult.fold(
                    onSuccess = { outcome ->
                        idempotentResult(
                            HttpStatusCode.Accepted,
                            "",
                            afterCommitBestEffort = { vehiclePingService.dispatchPing(outcome) },
                        )
                    },
                    onError = { error ->
                        idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name))
                    },
                )
            }
        }

        vehiclePingLiveRoute(hub, accessTokenSessionValidator)
    }
}

@Suppress("DEPRECATION") // Reject before the SSE handler commits a 200 response.
private fun Route.vehiclePingLiveRoute(
    hub: VehiclePingHub,
    accessTokenSessionValidator: AccessTokenSessionValidator,
) {
    route(VEHICLE_PING_LIVE_PATH) {
        intercept(ApplicationCallPipeline.Call) {
            if (!context.requireRole(UserRole.VENDOR)) finish()
        }

        sse {
            val vendorUserId = call.currentUserIdOrNull() ?: return@sse
            val familyId = call.currentTokenFamilyIdOrNull() ?: return@sse
            val remainingTokenValidity = call.remainingJwtValidityOrNull() ?: return@sse
            if (remainingTokenValidity <= Duration.ZERO) return@sse

            heartbeat { period = SSE_HEARTBEAT_PERIOD }

            launch {
                delay((remainingTokenValidity - RECONNECT_WARNING_LEAD_TIME).coerceAtLeast(Duration.ZERO))
                send(ServerSentEvent(event = SSE_EVENT_RECONNECT))
            }

            withTimeoutOrNull(remainingTokenValidity) {
                hub.subscribe(vendorUserId).collectWhileAccessSessionActive(
                    userId = vendorUserId,
                    familyId = familyId,
                    validator = accessTokenSessionValidator,
                ) { notification ->
                    send(
                        ServerSentEvent(
                            data = Json.encodeToString(notification.toResponseDto()),
                            event = SSE_EVENT_PING,
                        ),
                    )
                }
            }
        }
    }
}

private fun PingError.toHttpStatusCode() =
    when (this) {
        PingError.VEHICLE_NOT_FOUND -> HttpStatusCode.NotFound
        PingError.VEHICLE_NOT_ACTIVE -> HttpStatusCode.Conflict
        PingError.COOLDOWN_ACTIVE -> HttpStatusCode.TooManyRequests
    }
