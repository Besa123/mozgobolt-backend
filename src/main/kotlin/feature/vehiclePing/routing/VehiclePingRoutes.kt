package com.mozgobolt.feature.vehiclePing.routing

import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RECONNECT_WARNING_LEAD_TIME
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.SSE_EVENT_RECONNECT
import com.mozgobolt.core.modules.plugin.SSE_HEARTBEAT_PERIOD
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.currentUserRoleOrNull
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
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration

const val VEHICLE_PING_LIVE_PATH = "/vehicle-tracking/pings/live"

private const val INVALID_VEHICLE_ID = "INVALID_VEHICLE_ID"
private const val SSE_EVENT_PING = "ping"

/**
 * `requireRole` (the shared [com.mozgobolt.feature.user.routing.RoleGuard]) only extends
 * `RoutingContext` — `sse { }`'s receiver is [ServerSSESession], a different, unrelated type that
 * merely also exposes `call` — so the SSE route below checks the role claim directly instead of
 * reusing that helper.
 */
private fun ServerSSESession.hasRole(role: UserRole): Boolean = call.currentUserRoleOrNull() == role.name

fun Route.vehiclePingRoutes(
    vehiclePingService: VehiclePingService,
    hub: VehiclePingHub,
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

            vehiclePingService
                .ping(
                    buyerUserId = buyerUserId,
                    vehicleId = vehicleId,
                    latitude = request.latitude,
                    longitude = request.longitude,
                ).fold(
                    onSuccess = { call.respond(HttpStatusCode.Accepted) },
                    onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
                )
        }

        sse(VEHICLE_PING_LIVE_PATH) {
            val vendorUserId = call.currentUserIdOrNull() ?: return@sse
            if (!hasRole(UserRole.VENDOR)) return@sse
            val remainingTokenValidity = call.remainingJwtValidityOrNull() ?: return@sse
            if (remainingTokenValidity <= Duration.ZERO) return@sse

            heartbeat { period = SSE_HEARTBEAT_PERIOD }

            launch {
                delay((remainingTokenValidity - RECONNECT_WARNING_LEAD_TIME).coerceAtLeast(Duration.ZERO))
                send(ServerSentEvent(event = SSE_EVENT_RECONNECT))
            }

            withTimeoutOrNull(remainingTokenValidity) {
                hub.subscribe(vendorUserId).collect { notification ->
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
