package com.shelflife.feature.pantryEntry.routing

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
import com.shelflife.feature.pantryEntry.domain.PantryEntryService
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome
import com.shelflife.feature.pantryEntry.routing.dto.request.CreatePantryEntryRequestDto
import com.shelflife.feature.pantryEntry.routing.dto.request.UpdatePantryEntryRequestDto
import com.shelflife.feature.pantryEntry.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.math.BigDecimal
import java.time.LocalDate

fun Route.pantryEntryRoutes(
    pantryEntryService: PantryEntryService,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi {
        registerListRoute(pantryEntryService)
        registerCreateRoute(pantryEntryService, idempotencyStore)
        registerUpdateRoute(pantryEntryService)
        registerDeleteRoute(pantryEntryService)
    }
}

private fun Route.registerListRoute(pantryEntryService: PantryEntryService) {
    get("/pantry-entries") {
        val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)

        val afterId = call.request.queryParameters["afterId"]?.toIntOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()

        val page = pantryEntryService.listForUser(userId = userId, afterId = afterId, limit = limit)
        call.respond(HttpStatusCode.OK, page.toResponseDto())
    }
}

private fun Route.registerCreateRoute(
    pantryEntryService: PantryEntryService,
    idempotencyStore: IdempotencyStore,
) {
    validatedPost<CreatePantryEntryRequestDto>("/pantry-entries", BodyLimit.SMALL, RequestTimeout.FAST) { request ->
        val userId =
            call.currentUserIdOrNull()
                ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

        val productReference =
            request.productId?.let { ProductReference.Existing(it) }
                ?: ProductReference.New(requireNotNull(request.newProductName))

        val fingerprint =
            "$userId:${request.productId}:${request.newProductName}:" +
                "${request.quantityAmount}:${request.unitId}:${request.expirationDate}"

        idempotent(idempotencyStore, requestFingerprint = fingerprint) {
            pantryEntryService
                .createEntry(
                    userId = userId,
                    product = productReference,
                    fields =
                        PantryEntryFields(
                            storageLocationId = request.storageLocationId,
                            unitId = request.unitId,
                            quantityAmount = BigDecimal.valueOf(request.quantityAmount),
                            expirationDate = request.expirationDate?.let { LocalDate.parse(it) },
                            brandOrNote = request.brandOrNote,
                        ),
                ).fold(
                    onSuccess = { entry -> idempotentResult(HttpStatusCode.Created, entry.toResponseDto()) },
                    onError = { error ->
                        idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name))
                    },
                )
        }
    }
}

private fun Route.registerUpdateRoute(pantryEntryService: PantryEntryService) {
    validatedPatch<UpdatePantryEntryRequestDto>(
        "/pantry-entries/{id}",
        BodyLimit.SMALL,
        RequestTimeout.FAST,
    ) { request ->
        val userId =
            call.currentUserIdOrNull()
                ?: return@validatedPatch call.respond(HttpStatusCode.Unauthorized)
        val entryId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@validatedPatch call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "INVALID_ENTRY_ID"),
                )

        pantryEntryService
            .updateEntry(
                userId = userId,
                entryId = entryId,
                fields =
                    PantryEntryFields(
                        storageLocationId = request.storageLocationId,
                        unitId = request.unitId,
                        quantityAmount = BigDecimal.valueOf(request.quantityAmount),
                        expirationDate = request.expirationDate?.let { LocalDate.parse(it) },
                        brandOrNote = request.brandOrNote,
                    ),
            ).fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is UpdateEntryOutcome.Updated ->
                            call.respond(HttpStatusCode.OK, outcome.entry.toResponseDto())

                        is UpdateEntryOutcome.Deleted ->
                            call.respond(HttpStatusCode.NoContent)
                    }
                },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
    }
}

private fun Route.registerDeleteRoute(pantryEntryService: PantryEntryService) {
    limitedDelete("/pantry-entries/{id}", BodyLimit.TINY, RequestTimeout.FAST) {
        val userId =
            call.currentUserIdOrNull()
                ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
        val entryId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@limitedDelete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "INVALID_ENTRY_ID"),
                )

        pantryEntryService
            .deleteEntry(userId = userId, entryId = entryId)
            .fold(
                onSuccess = { call.respond(HttpStatusCode.NoContent) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
    }
}

private fun PantryEntryError.toHttpStatusCode() =
    when (this) {
        PantryEntryError.NOT_FOUND -> HttpStatusCode.NotFound
        PantryEntryError.PRODUCT_NOT_VISIBLE -> HttpStatusCode.BadRequest
        PantryEntryError.PRODUCT_NAME_ALREADY_EXISTS -> HttpStatusCode.Conflict
        PantryEntryError.STORAGE_LOCATION_NOT_FOUND -> HttpStatusCode.BadRequest
        PantryEntryError.UNIT_NOT_FOUND -> HttpStatusCode.BadRequest
    }
