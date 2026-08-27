package com.shelflife.feature.product.routing

import com.shelflife.core.data.idempotency.FakeIdempotencyStore
import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.installTestModules
import com.shelflife.core.routing.apiV1
import com.shelflife.core.routing.dto.response.ErrorResponse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json

fun Application.installProductRoutesTestApp(
    productService: FakeProductService = FakeProductService(),
    idempotencyStore: IdempotencyStore = FakeIdempotencyStore(),
) {
    installTestModules()
    routing {
        apiV1 {
            productRoutes(productService, idempotencyStore)
        }
    }
}

suspend fun ApplicationTestBuilder.postJson(
    path: String,
    body: String,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        block()
    }

suspend fun ApplicationTestBuilder.patchJson(
    path: String,
    body: String,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.patch(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        block()
    }

suspend fun HttpResponse.errorBody(): ErrorResponse = Json.decodeFromString(bodyAsText())
