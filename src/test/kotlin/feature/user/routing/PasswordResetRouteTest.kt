/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.user.domain.model.PasswordResetError
import com.mozgobolt.feature.user.routing.dto.response.MessageResponseDto
import com.mozgobolt.feature.user.routing.dto.response.PasswordResetValidateResponseDto
import io.ktor.client.request.header
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

    @Test
    fun `a retry with the same idempotency key replays the reset request`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installAuthRoutesTestApp(userService, idempotencyStore) }
            val body = """{"email":"user@example.com"}"""

            val first =
                postJson(AuthPaths.PASSWORD_RESET_REQUEST, body) { header("Idempotency-Key", "reset-request-1") }
            val replay =
                postJson(AuthPaths.PASSWORD_RESET_REQUEST, body) { header("Idempotency-Key", "reset-request-1") }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["X-Idempotency-Replayed"])
            assertEquals(listOf("user@example.com"), userService.requestPasswordResetCalls)
            assertEquals(first.bodyAsText(), replay.bodyAsText())
        }

    @Test
    fun `a reset request idempotency key cannot be reused for another email`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installAuthRoutesTestApp(userService, idempotencyStore) }

            val first =
                postJson(AuthPaths.PASSWORD_RESET_REQUEST, """{"email":"first@example.com"}""") {
                    header("Idempotency-Key", "reset-request-2")
                }
            val conflict =
                postJson(AuthPaths.PASSWORD_RESET_REQUEST, """{"email":"second@example.com"}""") {
                    header("Idempotency-Key", "reset-request-2")
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, conflict.status)
            assertEquals(listOf("first@example.com"), userService.requestPasswordResetCalls)
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
    fun `a token whose user no longer exists returns 400`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    validatePasswordResetTokenResult = AppResult.Error(PasswordResetError.USER_NOT_FOUND)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = postJson(AuthPaths.PASSWORD_RESET_VALIDATE, """{"token":"orphaned-token"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("USER_NOT_FOUND", response.errorBody().error)
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
                    """{"token":"some-token","newPassword":"NewPassphrase1x"}""",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                FakeUserService.ConfirmPasswordResetCall(token = "some-token", newPassword = "NewPassphrase1x"),
                userService.lastConfirmPasswordResetCall,
            )
            Json.decodeFromString<MessageResponseDto>(response.bodyAsText())
        }

    @Test
    fun `a retry with the same idempotency key replays a successful password reset`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installAuthRoutesTestApp(userService, idempotencyStore) }
            val body = """{"token":"some-token","newPassword":"NewPassphrase1x"}"""

            val first =
                postJson(AuthPaths.PASSWORD_RESET_CONFIRM, body) {
                    header("Idempotency-Key", "reset-confirm-1")
                }
            val replay =
                postJson(AuthPaths.PASSWORD_RESET_CONFIRM, body) {
                    header("Idempotency-Key", "reset-confirm-1")
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["X-Idempotency-Replayed"])
            assertEquals(1, userService.confirmPasswordResetCallCount)
            assertEquals(first.bodyAsText(), replay.bodyAsText())
        }

    @Test
    fun `a password reset confirmation key cannot be reused with a different password`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installAuthRoutesTestApp(userService, idempotencyStore) }

            val first =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"some-token","newPassword":"NewPassphrase1x"}""",
                ) {
                    header("Idempotency-Key", "reset-confirm-2")
                }
            val conflict =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"some-token","newPassword":"OtherPassphrase2x"}""",
                ) {
                    header("Idempotency-Key", "reset-confirm-2")
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, conflict.status)
            assertEquals(1, userService.confirmPasswordResetCallCount)
        }

    @Test
    fun `a password check outage returns 503 and allows retry with the same idempotency key`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    confirmPasswordResetResult = AppResult.Error(PasswordResetError.PASSWORD_CHECK_UNAVAILABLE)
                }
            val idempotencyStore = FakeIdempotencyStore()
            application { installAuthRoutesTestApp(userService, idempotencyStore) }
            val body = """{"token":"some-token","newPassword":"NewPassphrase1x"}"""

            val first =
                postJson(AuthPaths.PASSWORD_RESET_CONFIRM, body) {
                    header("Idempotency-Key", "password-check-retry")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, first.status)
            assertEquals("PASSWORD_CHECK_UNAVAILABLE", first.errorBody().error)
            assertEquals(null, idempotencyStore.find("password-check-retry"))

            userService.confirmPasswordResetResult = AppResult.Success(Unit)
            val retry =
                postJson(AuthPaths.PASSWORD_RESET_CONFIRM, body) {
                    header("Idempotency-Key", "password-check-retry")
                }

            assertEquals(HttpStatusCode.OK, retry.status)
            assertEquals(2, userService.confirmPasswordResetCallCount)
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
                    """{"token":"expired-token","newPassword":"NewPassphrase1x"}""",
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
                    """{"token":"some-token","newPassword":"NewPassphrase1x"}""",
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
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
            assertEquals(0, userService.confirmPasswordResetCallCount)
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
                    """{"token":"some-token","newPassword":"SamePassphrase1x"}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("SAME_AS_OLD", response.errorBody().error)
        }

    @Test
    fun `a token whose user no longer exists returns 400 on confirm`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    confirmPasswordResetResult = AppResult.Error(PasswordResetError.USER_NOT_FOUND)
                }
            application { installAuthRoutesTestApp(userService) }

            val response =
                postJson(
                    AuthPaths.PASSWORD_RESET_CONFIRM,
                    """{"token":"orphaned-token","newPassword":"NewPassphrase1x"}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("USER_NOT_FOUND", response.errorBody().error)
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
