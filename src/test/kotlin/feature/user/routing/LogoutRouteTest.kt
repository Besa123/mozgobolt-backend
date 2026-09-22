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
    fun `logout with a valid access token revokes the presented refresh token`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(AuthPaths.LOGOUT, """{"refreshToken":"some-refresh-token"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                listOf(FakeUserService.LogoutCall(userId = 1, refreshToken = "some-refresh-token")),
                userService.logoutUserCalls,
            )
        }

    @Test
    fun `logout without an access token is rejected`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.LOGOUT, """{"refreshToken":"some-refresh-token"}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), userService.logoutUserCalls)
        }

    @Test
    fun `logout with a blank refresh token fails validation`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(AuthPaths.LOGOUT, """{"refreshToken":""}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `logout-all with a valid access token revokes every session for that user`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = client.post(AuthPaths.LOGOUT_ALL) { bearerAuth(testAccessTokenFor(userId = 42)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(42), userService.logoutAllSessionsCalls)
        }

    @Test
    fun `logout-all without an access token is rejected`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = client.post(AuthPaths.LOGOUT_ALL)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), userService.logoutAllSessionsCalls)
        }
}
