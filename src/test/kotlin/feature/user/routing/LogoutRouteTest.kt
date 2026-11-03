/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.testAccessTokenFor
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class LogoutRouteTest {
    @Test
    fun `logout uses the signed session family without sending the refresh token`() =
        testApplication {
            configureTestEnvironment()
            val userSessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = userSessionService) }

            val response =
                client.post(AuthPaths.LOGOUT) {
                    bearerAuth(testAccessTokenFor(userId = 1, familyId = "family-1"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                listOf(FakeUserSessionService.LogoutCall(userId = 1, familyId = "family-1")),
                userSessionService.logoutUserCalls,
            )
        }

    @Test
    fun `logout without an access token is rejected`() =
        testApplication {
            configureTestEnvironment()
            val userSessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = userSessionService) }

            val response = client.post(AuthPaths.LOGOUT)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), userSessionService.logoutUserCalls)
        }

    @Test
    fun `logout rejects an access token without a session family`() =
        testApplication {
            configureTestEnvironment()
            val userSessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = userSessionService) }

            val response =
                client.post(AuthPaths.LOGOUT) {
                    bearerAuth(testAccessTokenFor(userId = 1, familyId = ""))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `logout-all with a valid access token revokes every session for that user`() =
        testApplication {
            configureTestEnvironment()
            val userSessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = userSessionService) }

            val response = client.post(AuthPaths.LOGOUT_ALL) { bearerAuth(testAccessTokenFor(userId = 42)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(42), userSessionService.logoutAllSessionsCalls)
        }

    @Test
    fun `logout-all without an access token is rejected`() =
        testApplication {
            configureTestEnvironment()
            val userSessionService = FakeUserSessionService()
            application { installAuthRoutesTestApp(userSessionService = userSessionService) }

            val response = client.post(AuthPaths.LOGOUT_ALL)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), userSessionService.logoutAllSessionsCalls)
        }
}
