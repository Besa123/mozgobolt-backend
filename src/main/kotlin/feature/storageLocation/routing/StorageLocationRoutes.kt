package com.shelflife.feature.storageLocation.routing

import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.data.idempotency.idempotent
import com.shelflife.core.data.idempotency.idempotentResult
import com.shelflife.core.modules.plugin.BodyLimit
import com.shelflife.core.modules.plugin.RequestTimeout
import com.shelflife.core.modules.plugin.limitedDelete
import com.shelflife.core.modules.plugin.validatedPatch
import com.shelflife.core.modules.plugin.validatedPost
import com.shelflife.core.routing.dto.response.ErrorResponse
import com.shelflife.core.utility.functions.currentUserIdOrNull
import com.shelflife.core.utility.functions.protectedApi
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError
import com.shelflife.feature.storageLocation.routing.dto.request.CreateStorageLocationRequestDto
import com.shelflife.feature.storageLocation.routing.dto.request.RenameStorageLocationRequestDto
import com.shelflife.feature.storageLocation.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.storageLocationRoutes(
    storageLocationService: StorageLocationService,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi {
        get("/storage-locations") {
            val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val locations = storageLocationService.listByUserId(userId)
            call.respond(HttpStatusCode.OK, locations.map { it.toResponseDto() })
        }

        validatedPost<CreateStorageLocationRequestDto>(
            "/storage-locations",
            BodyLimit.SMALL,
            RequestTimeout.STANDARD,
        ) { request ->
            val userId =
                call.currentUserIdOrNull()
                    ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

            idempotent(idempotencyStore, requestFingerprint = "$userId:${Json.encodeToString(request)}") {
                storageLocationService
                    .createForUser(userId = userId, name = request.name)
                    .fold(
                        onSuccess = { location ->
                            idempotentResult(
                                HttpStatusCode.Created,
                                location.toResponseDto(),
                            )
                        },
                        onError = { error ->
                            idempotentResult(
                                error.toHttpStatusCode(),
                                ErrorResponse(error = error.name),
                            )
                        },
                    )
            }
        }

        validatedPatch<RenameStorageLocationRequestDto>(
            "/storage-locations/{id}",
            BodyLimit.SMALL,
            RequestTimeout.STANDARD,
        ) { request ->
            val userId =
                call.currentUserIdOrNull()
                    ?: return@validatedPatch call.respond(HttpStatusCode.Unauthorized)
            val locationId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@validatedPatch call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "INVALID_LOCATION_ID"),
                    )

            storageLocationService
                .renameLocation(userId = userId, locationId = locationId, newName = request.name)
                .fold(
                    onSuccess = { location ->
                        call.respond(HttpStatusCode.OK, location.toResponseDto())
                    },
                    onError = { error ->
                        call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name))
                    },
                )
        }

        limitedDelete("/storage-locations/{id}", BodyLimit.TINY, RequestTimeout.STANDARD) {
            val userId =
                call.currentUserIdOrNull()
                    ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
            val locationId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@limitedDelete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "INVALID_LOCATION_ID"),
                    )

            storageLocationService
                .deleteLocation(userId = userId, locationId = locationId)
                .fold(
                    onSuccess = { call.respond(HttpStatusCode.NoContent) },
                    onError = { error ->
                        call.respond(
                            error.toHttpStatusCode(),
                            ErrorResponse(error = error.name),
                        )
                    },
                )
        }
    }
}

private fun StorageLocationError.toHttpStatusCode() =
    when (this) {
        StorageLocationError.DUPLICATE_NAME -> HttpStatusCode.Conflict
        StorageLocationError.NOT_FOUND -> HttpStatusCode.NotFound
    }
