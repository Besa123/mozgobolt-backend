/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.feature.user.domain.UserSessionService
import com.mozgobolt.feature.user.domain.model.AccountDeletionError
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

fun Application.installAuthRoutesTestApp(
    userService: FakeUserService = FakeUserService(),
    idempotencyStore: IdempotencyStore = FakeIdempotencyStore(),
    userSessionService: FakeUserSessionService = FakeUserSessionService(),
) {
    installTestModules()
    routing {
        apiV1 {
            authRoutes(userService, userSessionService, idempotencyStore)
        }
    }
}

class FakeUserSessionService : UserSessionService {
    data class LogoutCall(
        val userId: Int,
        val familyId: String,
    )

    data class DeleteAccountCall(
        val userId: Int,
        val currentPassword: String,
    )

    val logoutUserCalls = mutableListOf<LogoutCall>()
    val logoutAllSessionsCalls = mutableListOf<Int>()
    val deleteAccountCalls = mutableListOf<DeleteAccountCall>()
    var deleteAccountResult: AppResult<Unit, AccountDeletionError> = AppResult.Success(Unit)

    override suspend fun logoutUser(
        userId: Int,
        familyId: String,
    ) {
        logoutUserCalls += LogoutCall(userId, familyId)
    }

    override suspend fun logoutAllSessions(userId: Int) {
        logoutAllSessionsCalls += userId
    }

    override suspend fun deleteAccount(
        userId: Int,
        currentPassword: String,
    ): AppResult<Unit, AccountDeletionError> {
        deleteAccountCalls += DeleteAccountCall(userId, currentPassword)
        return deleteAccountResult
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
