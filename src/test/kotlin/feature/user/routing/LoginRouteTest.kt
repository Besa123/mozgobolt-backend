package com.besa.shelflife.feature.user.routing

import com.besa.shelflife.core.configureTestEnvironment
import com.besa.shelflife.core.domain.AppResult
import com.besa.shelflife.feature.user.domain.model.AuthResponse
import com.besa.shelflife.feature.user.domain.model.LoginError
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginRouteTest {
    @Test
    fun `correct credentials return 200 with both tokens`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    signInResult = AppResult.Success(AuthResponse(accessToken = "at", refreshToken = "rt"))
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.LOGIN, VALID_BODY)

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"at\""))
            assertTrue(response.bodyAsText().contains("\"rt\""))
        }

    @Test
    fun `invalid credentials return 401 with no body detail`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService().apply { signInResult = AppResult.Error(LoginError.INVALID_CREDENTIALS) }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.LOGIN, """{"email":"user@example.com","password":"wrong"}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a locked account returns 429`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService().apply { signInResult = AppResult.Error(LoginError.ACCOUNT_LOCKED) }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.LOGIN, VALID_BODY)

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("ACCOUNT_LOCKED", response.errorBody().error)
        }

    @Test
    fun `a blank password fails validation before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.LOGIN, """{"email":"user@example.com","password":""}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
        }

    @Test
    fun `a body over the 1KB login limit is rejected with 413`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }
            val oversizedPassword = "a".repeat(2 * 1024)

            val response = postJson(AuthPaths.LOGIN, """{"email":"user@example.com","password":"$oversizedPassword"}""")

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `exceeding five login attempts in the rate limit window returns 429 from the limiter itself`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService().apply { signInResult = AppResult.Error(LoginError.INVALID_CREDENTIALS) }
            application { installAuthRoutesTestApp(userService) }

            val statuses = (1..6).map { postJson(AuthPaths.LOGIN, VALID_BODY).status }

            assertEquals(listOf(HttpStatusCode.Unauthorized), statuses.take(5).distinct())
            assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
        }

    private companion object {
        const val VALID_BODY = """{"email":"user@example.com","password":"Str0ngPass1"}"""
    }
}
