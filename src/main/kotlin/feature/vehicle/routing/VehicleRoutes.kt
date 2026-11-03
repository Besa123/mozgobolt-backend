/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.routing

import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.data.idempotency.idempotent
import com.mozgobolt.core.data.idempotency.idempotentResult
import com.mozgobolt.core.domain.contact.messengerLink
import com.mozgobolt.core.domain.contact.viberLink
import com.mozgobolt.core.domain.contact.whatsAppLink
import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.UPLOAD_LIMIT
import com.mozgobolt.core.modules.plugin.limitedFileUploadPost
import com.mozgobolt.core.modules.plugin.limitedPost
import com.mozgobolt.core.modules.plugin.validatedPatch
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.routing.requireRole
import com.mozgobolt.feature.vehicle.domain.VehicleService
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import com.mozgobolt.feature.vehicle.routing.dto.request.CreateVehicleRequestDto
import com.mozgobolt.feature.vehicle.routing.dto.request.UpdateVehicleRequestDto
import com.mozgobolt.feature.vehicle.routing.dto.response.VehicleResponseDto
import com.mozgobolt.feature.vehicle.routing.dto.response.toResponseDto
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val INVALID_COMPANY_ID = "INVALID_COMPANY_ID"
private const val INVALID_VEHICLE_ID = "INVALID_VEHICLE_ID"
private const val PARAM_COMPANY_ID = "companyId"

fun Route.vehicleRoutes(
    vehicleService: VehicleService,
    vehicleAssignmentService: VehicleAssignmentService,
    userService: UserService,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi {
        registerCreateVehicleRoute(vehicleService, idempotencyStore)
        registerUpdateVehicleRoute(vehicleService)
        registerArchiveVehicleRoute(vehicleService)
        registerCompanyVehicleListRoute(vehicleService, vehicleAssignmentService, userService)

        // Deliberately not company/membership-scoped: any authenticated user viewing a vehicle on
        // the live map can fetch its details and picture.
        get("/vehicles/{id}") { handleGetVehicle(vehicleService, vehicleAssignmentService, userService) }
        get("/vehicles/{id}/picture") { handleGetVehiclePicture(vehicleService) }
    }

    // A stricter rate limit than the rest of this feature, matching this codebase's established
    // convention for file uploads (UPLOAD_LIMIT, 10/min).
    protectedApi(limitName = UPLOAD_LIMIT) {
        limitedFileUploadPost("/companies/{companyId}/vehicles/{id}/picture", BodyLimit.LARGE) { bytes ->
            handleUploadVehiclePicture(vehicleService, bytes)
        }
    }
}

private fun Route.registerCreateVehicleRoute(
    vehicleService: VehicleService,
    idempotencyStore: IdempotencyStore,
) {
    validatedPost<CreateVehicleRequestDto>(
        "/companies/{id}/vehicles",
        BodyLimit.TINY,
        RequestTimeout.STANDARD,
    ) { request ->
        if (!requireRole(UserRole.VENDOR)) return@validatedPost
        val userId = call.currentUserIdOrNull() ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@validatedPost call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_COMPANY_ID),
                )

        // A network retry re-sending the same "add this vehicle" request must not create a
        // second vehicle with the same label.
        idempotent(
            idempotencyStore,
            requestFingerprint = "$userId:$companyId:${Json.encodeToString(request)}",
        ) {
            val createResult =
                vehicleService.createVehicle(
                    adminUserId = userId,
                    companyId = companyId,
                    label = request.label,
                    licensePlate = request.licensePlate,
                    pictureUrl = request.pictureUrl,
                )
            createResult.fold(
                onSuccess = { vehicle -> idempotentResult(HttpStatusCode.Created, vehicle.toResponseDto()) },
                onError = { error -> idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }
    }
}

private fun Route.registerUpdateVehicleRoute(vehicleService: VehicleService) {
    validatedPatch<UpdateVehicleRequestDto>(
        "/companies/{companyId}/vehicles/{id}",
        BodyLimit.TINY,
        RequestTimeout.STANDARD,
    ) { request ->
        if (!requireRole(UserRole.VENDOR)) return@validatedPatch
        val userId = call.currentUserIdOrNull() ?: return@validatedPatch call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters[PARAM_COMPANY_ID]?.toIntOrNull()
                ?: return@validatedPatch call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_COMPANY_ID),
                )
        val vehicleId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@validatedPatch call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_VEHICLE_ID),
                )

        val updateResult =
            vehicleService.updateVehicle(
                adminUserId = userId,
                companyId = companyId,
                vehicleId = vehicleId,
                licensePlate = request.licensePlate,
                pictureUrl = request.pictureUrl,
                clearPictureUrl = request.clearPictureUrl,
            )
        updateResult.fold(
            onSuccess = { vehicle -> call.respond(HttpStatusCode.OK, vehicle.toResponseDto()) },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }
}

private fun Route.registerArchiveVehicleRoute(vehicleService: VehicleService) {
    // No idempotent{} wrapper: archiving is already naturally idempotent at the service layer
    // (an already-archived vehicle short-circuits to Success), the same reasoning already
    // applied to company member promote/demote.
    limitedPost("/companies/{companyId}/vehicles/{id}/archive", timeout = RequestTimeout.STANDARD) {
        handleArchiveVehicle(vehicleService)
    }
}

private fun Route.registerCompanyVehicleListRoute(
    vehicleService: VehicleService,
    vehicleAssignmentService: VehicleAssignmentService,
    userService: UserService,
) {
    get("/companies/{id}/vehicles") {
        if (!requireRole(UserRole.VENDOR)) return@get
        val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_COMPANY_ID))

        vehicleService.listCompanyVehicles(userId = userId, companyId = companyId).fold(
            onSuccess = { vehicles ->
                val responses =
                    vehicles.map {
                        it.toResponseDtoWithDriverInfo(
                            vehicleAssignmentService,
                            userService,
                        )
                    }
                call.respond(HttpStatusCode.OK, responses)
            },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }
}

private suspend fun RoutingContext.handleUploadVehiclePicture(
    vehicleService: VehicleService,
    bytes: ByteArray,
) {
    if (!requireRole(UserRole.VENDOR)) return
    val userId = call.currentUserIdOrNull() ?: return call.respond(HttpStatusCode.Unauthorized)
    val companyId =
        call.parameters[PARAM_COMPANY_ID]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_COMPANY_ID))
    val vehicleId =
        call.parameters["id"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_VEHICLE_ID))

    val uploadResult =
        vehicleService.uploadVehiclePicture(
            adminUserId = userId,
            companyId = companyId,
            vehicleId = vehicleId,
            rawBytes = bytes,
        )
    uploadResult.fold(
        onSuccess = { vehicle -> call.respond(HttpStatusCode.OK, vehicle.toResponseDto()) },
        onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
    )
}

private suspend fun RoutingContext.handleGetVehicle(
    vehicleService: VehicleService,
    vehicleAssignmentService: VehicleAssignmentService,
    userService: UserService,
) {
    call.currentUserIdOrNull() ?: return call.respond(HttpStatusCode.Unauthorized)
    val vehicleId =
        call.parameters["id"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_VEHICLE_ID))

    vehicleService.findVehicle(vehicleId).fold(
        onSuccess = { vehicle ->
            call.respond(HttpStatusCode.OK, vehicle.toResponseDtoWithDriverInfo(vehicleAssignmentService, userService))
        },
        onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
    )
}

private suspend fun RoutingContext.handleGetVehiclePicture(vehicleService: VehicleService) {
    call.currentUserIdOrNull() ?: return call.respond(HttpStatusCode.Unauthorized)
    val vehicleId =
        call.parameters["id"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_VEHICLE_ID))

    vehicleService.getVehiclePicture(vehicleId).fold(
        onSuccess = { bytes -> call.respondBytes(bytes, ContentType.Image.JPEG) },
        onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
    )
}

private suspend fun RoutingContext.handleArchiveVehicle(vehicleService: VehicleService) {
    if (!requireRole(UserRole.VENDOR)) return
    val userId = call.currentUserIdOrNull() ?: return call.respond(HttpStatusCode.Unauthorized)
    val companyId =
        call.parameters[PARAM_COMPANY_ID]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_COMPANY_ID))
    val vehicleId =
        call.parameters["id"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_VEHICLE_ID))

    vehicleService.archiveVehicle(adminUserId = userId, companyId = companyId, vehicleId = vehicleId).fold(
        onSuccess = { call.respond(HttpStatusCode.NoContent) },
        onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
    )
}

/**
 * Enriches the plain domain [Vehicle] with its current driver's contact info, resolved fresh on
 * every listing call rather than stored anywhere — this is a low-traffic, company-scoped listing
 * (not the high-frequency SSE live-tracking feed), so a couple of extra lookups per vehicle here
 * is the right trade-off, not worth denormalizing. WhatsApp/Viber/Messenger are already-built,
 * ready-to-open deep links (see core.domain.contact.ContactLinks) — the client just opens them.
 */
@Suppress("CommentOverPrivateFunction")
private suspend fun Vehicle.toResponseDtoWithDriverInfo(
    vehicleAssignmentService: VehicleAssignmentService,
    userService: UserService,
): VehicleResponseDto {
    val activeAssignment = vehicleAssignmentService.findActiveForVehicle(id)
    val contactInfo = activeAssignment?.let { userService.findContactInfo(it.vendorUserId) }
    return toResponseDto(
        currentDriverPhoneNumber = contactInfo?.phoneNumber,
        currentDriverWhatsAppLink = contactInfo?.run { whatsappNumber?.let(::whatsAppLink) },
        currentDriverViberLink = contactInfo?.run { viberNumber?.let(::viberLink) },
        currentDriverMessengerLink = contactInfo?.run { messengerUsername?.let(::messengerLink) },
    )
}

private fun VehicleError.toHttpStatusCode() =
    when (this) {
        VehicleError.NOT_A_MEMBER -> HttpStatusCode.Forbidden
        VehicleError.NOT_ADMIN -> HttpStatusCode.Forbidden
        VehicleError.VEHICLE_NOT_FOUND -> HttpStatusCode.NotFound
        VehicleError.LICENSE_PLATE_TAKEN -> HttpStatusCode.Conflict
        VehicleError.INVALID_IMAGE -> HttpStatusCode.BadRequest
        VehicleError.VIRUS_SCANNER_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    }
