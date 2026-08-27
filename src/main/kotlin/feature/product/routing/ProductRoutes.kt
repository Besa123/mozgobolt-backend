package com.shelflife.feature.product.routing

import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.data.idempotency.idempotent
import com.shelflife.core.data.idempotency.idempotentResult
import com.shelflife.core.modules.plugin.BodyLimit
import com.shelflife.core.modules.plugin.RequestTimeout
import com.shelflife.core.modules.plugin.validatedPatch
import com.shelflife.core.modules.plugin.validatedPost
import com.shelflife.core.routing.dto.response.ErrorResponse
import com.shelflife.core.utility.functions.currentUserIdOrNull
import com.shelflife.core.utility.functions.protectedApi
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.product.routing.dto.request.CreateProductRequestDto
import com.shelflife.feature.product.routing.dto.request.RenameProductRequestDto
import com.shelflife.feature.product.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json

fun Route.productRoutes(
    productService: ProductService,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi {
        get("/products/search") {
            val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val query = call.request.queryParameters["q"].orEmpty()

            val results = productService.search(userId = userId, query = query)
            call.respond(HttpStatusCode.OK, results.map { it.toResponseDto() })
        }

        get("/products/mine") {
            val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val results = productService.listOwnedBy(userId)
            call.respond(HttpStatusCode.OK, results.map { it.toResponseDto() })
        }

        validatedPost<CreateProductRequestDto>("/products", BodyLimit.SMALL, RequestTimeout.STANDARD) { request ->
            val userId =
                call.currentUserIdOrNull()
                    ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

            idempotent(idempotencyStore, requestFingerprint = "$userId:${Json.encodeToString(request)}") {
                productService
                    .createPrivateProduct(
                        userId = userId,
                        name = request.name,
                        defaultLifespanDays = request.defaultLifespanDays,
                        defaultUnitCategory = request.defaultUnitCategory,
                    ).fold(
                        onSuccess = { product ->
                            idempotentResult(HttpStatusCode.Created, product.toResponseDto())
                        },
                        onError = { error ->
                            idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name))
                        },
                    )
            }
        }

        validatedPatch<RenameProductRequestDto>("/products/{id}", BodyLimit.SMALL, RequestTimeout.STANDARD) { request ->
            val userId =
                call.currentUserIdOrNull()
                    ?: return@validatedPatch call.respond(HttpStatusCode.Unauthorized)
            val productId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@validatedPatch call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "INVALID_PRODUCT_ID"),
                    )

            productService
                .renameProduct(userId = userId, productId = productId, newName = request.name)
                .fold(
                    onSuccess = { product -> call.respond(HttpStatusCode.OK, product.toResponseDto()) },
                    onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
                )
        }
    }
}

private fun ProductError.toHttpStatusCode() =
    when (this) {
        ProductError.DUPLICATE_NAME -> HttpStatusCode.Conflict
        ProductError.NOT_FOUND -> HttpStatusCode.NotFound
    }
