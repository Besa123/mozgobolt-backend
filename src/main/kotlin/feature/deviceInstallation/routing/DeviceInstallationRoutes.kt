package com.mozgobolt.feature.deviceInstallation.routing

import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.limitedDelete
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationError
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import com.mozgobolt.feature.deviceInstallation.routing.dto.request.RegisterDeviceInstallationRequestDto
import com.mozgobolt.feature.deviceInstallation.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private const val MISSING_INSTALLATION_ID = "MISSING_INSTALLATION_ID"

/**
 * No role restriction beyond authentication — both buyers and vendors receive push
 * notifications (pings for vendors today; a favorited company's proximity alert for buyers once
 * that ships), so any authenticated user may register/unregister a device installation.
 */
fun Route.deviceInstallationRoutes(deviceInstallationService: DeviceInstallationService) {
    protectedApi {
        validatedPost<RegisterDeviceInstallationRequestDto>(
            "/device-installations",
            BodyLimit.TINY,
            RequestTimeout.FAST,
        ) { request ->
            val userId = call.currentUserIdOrNull() ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

            val deviceInstallation =
                deviceInstallationService.registerInstallation(
                    userId = userId,
                    installationId = request.installationId,
                    platform = DevicePlatform.valueOf(request.platform),
                )
            call.respond(HttpStatusCode.OK, deviceInstallation.toResponseDto())
        }

        limitedDelete("/device-installations/{installationId}", timeout = RequestTimeout.FAST) {
            val userId = call.currentUserIdOrNull() ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
            val installationId =
                call.parameters["installationId"]
                    ?: return@limitedDelete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = MISSING_INSTALLATION_ID),
                    )

            deviceInstallationService.unregisterInstallation(userId, installationId).fold(
                onSuccess = { call.respond(HttpStatusCode.NoContent) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }
    }
}

private fun DeviceInstallationError.toHttpStatusCode() =
    when (this) {
        DeviceInstallationError.NOT_FOUND -> HttpStatusCode.NotFound
    }
