package com.mozgobolt.feature.vehicleAssignment.routing

import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.limitedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.routing.requireRole
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleAssignment.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private const val INVALID_VEHICLE_ID = "INVALID_VEHICLE_ID"

fun Route.vehicleAssignmentRoutes(vehicleAssignmentService: VehicleAssignmentService) {
    protectedApi {
        limitedPost("/vehicles/{id}/link", timeout = RequestTimeout.STANDARD) {
            if (!requireRole(UserRole.VENDOR)) return@limitedPost
            val userId = call.currentUserIdOrNull() ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)
            val vehicleId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@limitedPost call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = INVALID_VEHICLE_ID),
                    )

            vehicleAssignmentService.link(vendorUserId = userId, vehicleId = vehicleId).fold(
                onSuccess = { assignment -> call.respond(HttpStatusCode.OK, assignment.toResponseDto()) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }

        limitedPost("/vehicles/{id}/unlink", timeout = RequestTimeout.STANDARD) {
            if (!requireRole(UserRole.VENDOR)) return@limitedPost
            val userId = call.currentUserIdOrNull() ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)
            val vehicleId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@limitedPost call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = INVALID_VEHICLE_ID),
                    )

            vehicleAssignmentService.unlink(vendorUserId = userId, vehicleId = vehicleId).fold(
                onSuccess = { call.respond(HttpStatusCode.OK) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }

        limitedPost("/vehicles/{id}/assignments/end-active", timeout = RequestTimeout.STANDARD) {
            if (!requireRole(UserRole.VENDOR)) return@limitedPost
            val adminUserId =
                call.currentUserIdOrNull() ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)
            val vehicleId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@limitedPost call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = INVALID_VEHICLE_ID),
                    )

            vehicleAssignmentService.endActiveAssignment(adminUserId = adminUserId, vehicleId = vehicleId).fold(
                onSuccess = { call.respond(HttpStatusCode.OK) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }
    }
}

private fun VehicleAssignmentError.toHttpStatusCode() =
    when (this) {
        VehicleAssignmentError.VEHICLE_NOT_FOUND -> HttpStatusCode.NotFound
        VehicleAssignmentError.FORBIDDEN -> HttpStatusCode.Forbidden
        VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED -> HttpStatusCode.Conflict
        VehicleAssignmentError.VEHICLE_ARCHIVED -> HttpStatusCode.Conflict
        VehicleAssignmentError.NOT_LINKED -> HttpStatusCode.Conflict
        VehicleAssignmentError.NOT_ADMIN -> HttpStatusCode.Forbidden
        VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT -> HttpStatusCode.Conflict
    }
