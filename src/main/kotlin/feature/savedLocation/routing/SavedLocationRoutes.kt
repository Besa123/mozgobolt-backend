package com.mozgobolt.feature.savedLocation.routing

import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.data.idempotency.idempotent
import com.mozgobolt.core.data.idempotency.idempotentResult
import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.limitedDelete
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.feature.savedLocation.domain.SavedLocationService
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.routing.dto.request.CreateSavedLocationRequestDto
import com.mozgobolt.feature.savedLocation.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val INVALID_SAVED_LOCATION_ID = "INVALID_SAVED_LOCATION_ID"

fun Route.savedLocationRoutes(
    savedLocationService: SavedLocationService,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi {
        validatedPost<CreateSavedLocationRequestDto>(
            "/saved-locations",
            BodyLimit.TINY,
            RequestTimeout.STANDARD,
        ) { request ->
            val userId = call.currentUserIdOrNull() ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

            // A network retry re-sending the same "save this location" request must not create a
            // second, identical saved location.
            idempotent(idempotencyStore, requestFingerprint = "$userId:${Json.encodeToString(request)}") {
                val savedLocation =
                    savedLocationService.createSavedLocation(
                        userId = userId,
                        label = request.label,
                        latitude = request.latitude,
                        longitude = request.longitude,
                        radiusKm = request.radiusKm,
                    )
                idempotentResult(HttpStatusCode.Created, savedLocation.toResponseDto())
            }
        }

        get("/saved-locations") {
            val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            call.respond(
                HttpStatusCode.OK,
                savedLocationService.listSavedLocations(userId).map { it.toResponseDto() },
            )
        }

        limitedDelete("/saved-locations/{id}", timeout = RequestTimeout.FAST) {
            val userId = call.currentUserIdOrNull() ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
            val savedLocationId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@limitedDelete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = INVALID_SAVED_LOCATION_ID),
                    )

            savedLocationService.deleteSavedLocation(userId, savedLocationId).fold(
                onSuccess = { call.respond(HttpStatusCode.NoContent) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }
    }
}

private fun SavedLocationError.toHttpStatusCode() =
    when (this) {
        SavedLocationError.NOT_FOUND -> HttpStatusCode.NotFound
    }
