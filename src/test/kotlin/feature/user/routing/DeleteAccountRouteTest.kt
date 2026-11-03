/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.domain.model.AccountDeletionError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteAccountRouteTest {
    @Test
    fun `authenticated account deletion requires and forwards the current password`() =
        testApplication {
            configureTestEnvironment()
            val sessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = sessionService) }

            val response =
                client.post(AuthPaths.DELETE_ACCOUNT) {
                    bearerAuth(testAccessTokenFor(userId = 7))
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":"current password"}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(
                listOf(FakeUserSessionService.DeleteAccountCall(7, "current password")),
                sessionService.deleteAccountCalls,
            )
        }

    @Test
    fun `unauthenticated account deletion is rejected`() =
        testApplication {
            configureTestEnvironment()
            val sessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = sessionService) }

            val response =
                client.post(AuthPaths.DELETE_ACCOUNT) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":"current password"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), sessionService.deleteAccountCalls)
        }

    @Test
    fun `account deletion returns conflict while the caller is the last company admin`() =
        testApplication {
            configureTestEnvironment()
            val sessionService = FakeUserSessionService()
            sessionService.deleteAccountResult = AppResult.Error(AccountDeletionError.LAST_COMPANY_ADMIN)
            application { installAuthRoutesTestApp(userSessionService = sessionService) }

            val response =
                client.post(AuthPaths.DELETE_ACCOUNT) {
                    bearerAuth(testAccessTokenFor(userId = 7))
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":"current password"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("LAST_COMPANY_ADMIN", response.errorBody().error)
        }

    @Test
    fun `empty current password is rejected before the deletion service runs`() =
        testApplication {
            configureTestEnvironment()
            val sessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = sessionService) }

            val response =
                client.post(AuthPaths.DELETE_ACCOUNT) {
                    bearerAuth(testAccessTokenFor(userId = 7))
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":""}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(emptyList(), sessionService.deleteAccountCalls)
        }
}
