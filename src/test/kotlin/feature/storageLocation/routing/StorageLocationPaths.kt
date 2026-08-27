package com.shelflife.feature.storageLocation.routing

import com.shelflife.core.data.idempotency.FakeIdempotencyStore
import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.installTestModules
import com.shelflife.core.routing.apiV1
import com.shelflife.core.routing.dto.response.ErrorResponse
import com.shelflife.core.testAccessTokenFor
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
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

object StorageLocationPaths {
    private const val BASE = "/api/v1/storage-locations"
    const val LIST = BASE
    const val CREATE = BASE

    fun byId(id: Int) = "$BASE/$id"
}

fun Application.installStorageLocationRoutesTestApp(
    storageLocationService: StorageLocationService,
    idempotencyStore: IdempotencyStore = FakeIdempotencyStore(),
) {
    installTestModules()
    routing {
        apiV1 {
            storageLocationRoutes(storageLocationService, idempotencyStore)
        }
    }
}

/**
 * All storage-location routes sit behind `protectedApi { }`, so every call needs a bearer
 * token. Pass `userId = null` only when the test is specifically exercising the unauthenticated
 * (401) path — every other test should authenticate as a real user id.
 */
suspend fun ApplicationTestBuilder.getJson(
    path: String,
    userId: Int? = 1,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.get(path) {
        contentType(ContentType.Application.Json)
        userId?.let { bearerAuth(testAccessTokenFor(it)) }
        block()
    }

suspend fun ApplicationTestBuilder.postJson(
    path: String,
    body: String,
    userId: Int? = 1,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.post(path) {
        contentType(ContentType.Application.Json)
        userId?.let { bearerAuth(testAccessTokenFor(it)) }
        setBody(body)
        block()
    }

suspend fun ApplicationTestBuilder.patchJson(
    path: String,
    body: String,
    userId: Int? = 1,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.patch(path) {
        contentType(ContentType.Application.Json)
        userId?.let { bearerAuth(testAccessTokenFor(it)) }
        setBody(body)
        block()
    }

suspend fun ApplicationTestBuilder.deleteJson(
    path: String,
    userId: Int? = 1,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.delete(path) {
        contentType(ContentType.Application.Json)
        userId?.let { bearerAuth(testAccessTokenFor(it)) }
        block()
    }

suspend fun HttpResponse.errorBody(): ErrorResponse = Json.decodeFromString(bodyAsText())
