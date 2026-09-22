package com.mozgobolt.feature.sync.routing

import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.service.InMemorySyncEventHub
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder

object SyncPaths {
    private const val BASE = "/api/v1/sync"

    fun since(
        cursor: Long? = null,
        limit: Int? = null,
    ): String {
        val params =
            buildList {
                cursor?.let { add("since=$it") }
                limit?.let { add("limit=$it") }
            }
        return if (params.isEmpty()) BASE else "$BASE?${params.joinToString("&")}"
    }
}

fun Application.installSyncRoutesTestApp(
    syncService: SyncService,
    syncEventHub: SyncEventHub = InMemorySyncEventHub(),
) {
    installTestModules()
    routing {
        apiV1 {
            syncRoutes(syncService, syncEventHub)
        }
    }
}

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
