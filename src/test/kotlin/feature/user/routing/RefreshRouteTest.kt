package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.user.domain.model.AuthResponse
import com.mozgobolt.feature.user.domain.model.RefreshError
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshRouteTest {
    @Test
    fun `a valid refresh token returns 200 with a rotated token pair`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    refreshTokenResult =
                        AppResult.Success(AuthResponse(accessToken = "new-at", refreshToken = "new-rt"))
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.REFRESH, """{"refreshToken":"old-token"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("new-at"))
            assertTrue(response.bodyAsText().contains("new-rt"))
        }

    @Test
    fun `invalid credentials return 401 with no body detail`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    refreshTokenResult =
                        AppResult.Error(RefreshError.INVALID_CREDENTIALS)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.REFRESH, """{"refreshToken":"garbage"}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `detected token reuse returns 401 with an explicit error code`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply { refreshTokenResult = AppResult.Error(RefreshError.TOKEN_REUSE_DETECTED) }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.REFRESH, """{"refreshToken":"stolen-token"}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("TOKEN_REUSE_DETECTED", response.errorBody().error)
        }

    @Test
    fun `a blank refresh token fails validation before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.REFRESH, """{"refreshToken":""}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
        }

    @Test
    fun `a body over the 16KB refresh limit is rejected with 413`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }
            val oversizedToken = "a".repeat(17 * 1024)

            val response = postJson(AuthPaths.REFRESH, """{"refreshToken":"$oversizedToken"}""")

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }
}
