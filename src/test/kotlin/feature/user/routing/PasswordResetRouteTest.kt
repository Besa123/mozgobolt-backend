package com.shelflife.feature.user.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.user.domain.model.PasswordResetError
import com.shelflife.feature.user.routing.dto.response.MessageResponseDto
import com.shelflife.feature.user.routing.dto.response.PasswordResetValidateResponseDto
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordResetRouteTest {
    // --- /password-reset/request ---------------------------------------------------------

    @Test
    fun `a well-formed reset request returns 200 and calls the service`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(AuthPaths.PASSWORD_RESET_REQUEST, """{"email":"user@example.com"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("user@example.com"), userService.requestPasswordResetCalls)
            Json.decodeFromString<MessageResponseDto>(response.bodyAsText())
        }

    @Test
    fun `a blank email fails DTO validation before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.PASSWORD_RESET_REQUEST, """{"email":""}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
            assertEquals(emptyList(), userService.requestPasswordResetCalls)
        }

    // --- /password-reset/validate -------------------------------------------------------

    @Test
    fun `a valid token returns 200 with the associated email`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    validatePasswordResetTokenResult = AppResult.Success("user@example.com")
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.PASSWORD_RESET_VALIDATE, """{"token":"some-token"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("some-token"), userService.validatePasswordResetTokenCalls)
            val body = Json.decodeFromString<PasswordResetValidateResponseDto>(response.bodyAsText())
            assertEquals(PasswordResetValidateResponseDto(valid = true, email = "user@example.com"), body)
        }

    @Test
    fun `an invalid token returns 400`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    validatePasswordResetTokenResult = AppResult.Error(PasswordResetError.INVALID_TOKEN)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.PASSWORD_RESET_VALIDATE, """{"token":"bad-token"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_TOKEN", response.errorBody().error)
        }

    @Test
    fun `an expired token returns 410 Gone`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    validatePasswordResetTokenResult = AppResult.Error(PasswordResetError.EXPIRED_TOKEN)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.PASSWORD_RESET_VALIDATE, """{"token":"expired-token"}""")

            assertEquals(HttpStatusCode.Gone, response.status)
            assertEquals("EXPIRED_TOKEN", response.errorBody().error)
        }

    @Test
    fun `a blank token fails DTO validation before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.PASSWORD_RESET_VALIDATE, """{"token":""}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
            assertEquals(emptyList(), userService.validatePasswordResetTokenCalls)
        }

    // --- /password-reset/confirm ---------------------------------------------------------

    @Test
    fun `a well-formed confirmation returns 200 and calls the service`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"some-token","newPassword":"NewPass1x"}""",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                FakeUserService.ConfirmPasswordResetCall(token = "some-token", newPassword = "NewPass1x"),
                userService.lastConfirmPasswordResetCall,
            )
            Json.decodeFromString<MessageResponseDto>(response.bodyAsText())
        }

    @Test
    fun `an expired token returns 410 Gone on confirm`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    confirmPasswordResetResult = AppResult.Error(PasswordResetError.EXPIRED_TOKEN)
                }
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"expired-token","newPassword":"NewPass1x"}""",
                )

            assertEquals(HttpStatusCode.Gone, response.status)
            assertEquals("EXPIRED_TOKEN", response.errorBody().error)
        }

    @Test
    fun `a locked account returns 429 on confirm`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    confirmPasswordResetResult = AppResult.Error(PasswordResetError.ACCOUNT_LOCKED)
                }
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"some-token","newPassword":"NewPass1x"}""",
                )

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("ACCOUNT_LOCKED", response.errorBody().error)
        }

    @Test
    fun `a weak password returns 400 on confirm`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    confirmPasswordResetResult = AppResult.Error(PasswordResetError.WEAK_PASSWORD)
                }
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"some-token","newPassword":"weak"}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("WEAK_PASSWORD", response.errorBody().error)
        }

    @Test
    fun `reusing the same password returns 400 on confirm`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    confirmPasswordResetResult = AppResult.Error(PasswordResetError.SAME_AS_OLD)
                }
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"some-token","newPassword":"SamePass1x"}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("SAME_AS_OLD", response.errorBody().error)
        }

    @Test
    fun `blank fields fail DTO validation before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.PASSWORD_RESET_CONFIRM, """{"token":"","newPassword":""}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
            assertEquals(null, userService.lastConfirmPasswordResetCall)
        }
}
