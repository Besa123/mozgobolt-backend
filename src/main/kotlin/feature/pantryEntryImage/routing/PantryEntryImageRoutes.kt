package com.shelflife.feature.pantryEntryImage.routing

import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.data.idempotency.idempotent
import com.shelflife.core.data.idempotency.idempotentResult
import com.shelflife.core.modules.plugin.API_LIMIT
import com.shelflife.core.modules.plugin.BodyLimit
import com.shelflife.core.modules.plugin.RequestTimeout
import com.shelflife.core.modules.plugin.UPLOAD_LIMIT
import com.shelflife.core.modules.plugin.limitedDelete
import com.shelflife.core.modules.plugin.limitedFileUploadPost
import com.shelflife.core.modules.plugin.limitedFileUploadPut
import com.shelflife.core.routing.dto.response.ErrorResponse
import com.shelflife.core.utility.functions.currentDeviceIdOrNull
import com.shelflife.core.utility.functions.currentUserIdOrNull
import com.shelflife.core.utility.functions.protectedApi
import com.shelflife.core.utility.functions.sha256Hex
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageService
import com.shelflife.feature.pantryEntryImage.domain.model.ImageContent
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import com.shelflife.feature.pantryEntryImage.routing.dto.response.toResponseDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val ENTRY_IMAGES_PATH = "/pantry-entries/{entryId}/images"
private const val ENTRY_IMAGE_BY_ID_PATH = "/pantry-entries/{entryId}/images/{imageId}"
private const val ENTRY_IMAGE_CONTENT_PATH = "/pantry-entries/{entryId}/images/{imageId}/content"
private const val PARAM_ENTRY_ID = "entryId"
private const val PARAM_IMAGE_ID = "imageId"
private const val INVALID_ENTRY_ID = "INVALID_ENTRY_ID"
private const val INVALID_IMAGE_ID = "INVALID_IMAGE_ID"

fun Route.pantryEntryImageRoutes(
    pantryEntryImageService: PantryEntryImageService,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi(limitName = UPLOAD_LIMIT) {
        registerAddImageRoute(pantryEntryImageService, idempotencyStore)
        registerReplaceImageRoute(pantryEntryImageService)
    }

    protectedApi(limitName = API_LIMIT) {
        registerListImagesRoute(pantryEntryImageService)
        registerGetContentRoute(pantryEntryImageService)
        registerDeleteImageRoute(pantryEntryImageService)
    }
}

private fun Route.registerAddImageRoute(
    pantryEntryImageService: PantryEntryImageService,
    idempotencyStore: IdempotencyStore,
) {
    limitedFileUploadPost(ENTRY_IMAGES_PATH, BodyLimit.LARGE, RequestTimeout.UPLOAD) { bytes ->
        val userId =
            call.currentUserIdOrNull()
                ?: return@limitedFileUploadPost call.respond(HttpStatusCode.Unauthorized)

        val entryId =
            call.parameters[PARAM_ENTRY_ID]?.toIntOrNull()
                ?: return@limitedFileUploadPost call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_ENTRY_ID),
                )

        val fingerprint = "$userId:$entryId:${bytes.sha256Hex()}"

        idempotent(idempotencyStore, requestFingerprint = fingerprint) {
            pantryEntryImageService
                .addImage(
                    userId = userId,
                    entryId = entryId,
                    rawBytes = bytes,
                    originDeviceId = call.currentDeviceIdOrNull(),
                ).fold(
                    onSuccess = { image -> idempotentResult(HttpStatusCode.Created, image.toResponseDto()) },
                    onError = { error ->
                        idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name))
                    },
                )
        }
    }
}

private fun Route.registerReplaceImageRoute(pantryEntryImageService: PantryEntryImageService) {
    limitedFileUploadPut(ENTRY_IMAGE_BY_ID_PATH, BodyLimit.LARGE, RequestTimeout.UPLOAD) { bytes ->
        val userId =
            call.currentUserIdOrNull()
                ?: return@limitedFileUploadPut call.respond(HttpStatusCode.Unauthorized)

        val entryId =
            call.parameters[PARAM_ENTRY_ID]?.toIntOrNull()
                ?: return@limitedFileUploadPut call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_ENTRY_ID),
                )

        val imageId =
            call.parameters[PARAM_IMAGE_ID]?.toIntOrNull()
                ?: return@limitedFileUploadPut call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_IMAGE_ID),
                )

        pantryEntryImageService
            .replaceImage(
                userId = userId,
                entryId = entryId,
                imageId = imageId,
                rawBytes = bytes,
                originDeviceId = call.currentDeviceIdOrNull(),
            ).fold(
                onSuccess = { image -> call.respond(HttpStatusCode.OK, image.toResponseDto()) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
    }
}

private fun Route.registerListImagesRoute(pantryEntryImageService: PantryEntryImageService) {
    get(ENTRY_IMAGES_PATH) {
        val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val entryId =
            call.parameters[PARAM_ENTRY_ID]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_ENTRY_ID))

        pantryEntryImageService
            .listImages(userId, entryId)
            .fold(
                onSuccess = { images -> call.respond(HttpStatusCode.OK, images.map { it.toResponseDto() }) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
    }
}

private fun Route.registerGetContentRoute(pantryEntryImageService: PantryEntryImageService) {
    get(ENTRY_IMAGE_CONTENT_PATH) {
        val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)

        val entryId =
            call.parameters[PARAM_ENTRY_ID]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_ENTRY_ID))

        val imageId =
            call.parameters[PARAM_IMAGE_ID]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_IMAGE_ID))

        pantryEntryImageService
            .loadContent(userId, entryId, imageId)
            .fold(
                onSuccess = { content -> call.respondImageContent(content) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
    }
}

private suspend fun ApplicationCall.respondImageContent(content: ImageContent) {
    val quotedETag = "\"${content.eTag}\""
    response.header(HttpHeaders.CacheControl, "private, no-cache")
    response.header(HttpHeaders.ETag, quotedETag)

    if (request.header(HttpHeaders.IfNoneMatch) == quotedETag) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondBytes(content.bytes, ContentType.parse(content.contentType), HttpStatusCode.OK)
    }
}

private fun Route.registerDeleteImageRoute(pantryEntryImageService: PantryEntryImageService) {
    limitedDelete(ENTRY_IMAGE_BY_ID_PATH, BodyLimit.TINY, RequestTimeout.FAST) {
        val userId =
            call.currentUserIdOrNull()
                ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)

        val entryId =
            call.parameters[PARAM_ENTRY_ID]?.toIntOrNull()
                ?: return@limitedDelete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_ENTRY_ID),
                )

        val imageId =
            call.parameters[PARAM_IMAGE_ID]?.toIntOrNull()
                ?: return@limitedDelete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_IMAGE_ID),
                )

        pantryEntryImageService
            .deleteImage(userId, entryId, imageId, call.currentDeviceIdOrNull())
            .fold(
                onSuccess = { call.respond(HttpStatusCode.NoContent) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
    }
}

private fun PantryEntryImageError.toHttpStatusCode() =
    when (this) {
        PantryEntryImageError.ENTRY_NOT_FOUND -> HttpStatusCode.NotFound
        PantryEntryImageError.NOT_FOUND -> HttpStatusCode.NotFound
        PantryEntryImageError.TOO_MANY_IMAGES -> HttpStatusCode.Conflict
        PantryEntryImageError.UNSUPPORTED_FORMAT -> HttpStatusCode.UnprocessableEntity
        PantryEntryImageError.DIMENSIONS_TOO_LARGE -> HttpStatusCode.UnprocessableEntity
        PantryEntryImageError.CORRUPT_IMAGE -> HttpStatusCode.UnprocessableEntity
        PantryEntryImageError.MALWARE_DETECTED -> HttpStatusCode.UnprocessableEntity
        PantryEntryImageError.SCAN_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
        PantryEntryImageError.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    }
