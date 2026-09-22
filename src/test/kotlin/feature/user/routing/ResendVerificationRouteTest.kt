package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ResendVerificationRouteTest {
    @Test
    fun `a valid access token resends the verification email for that user`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = client.post(AuthPaths.RESEND_VERIFICATION) { bearerAuth(testAccessTokenFor(userId = 7)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(7), userService.resendVerificationEmailCalls)
        }

    @Test
    fun `without an access token the request is rejected`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = client.post(AuthPaths.RESEND_VERIFICATION)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), userService.resendVerificationEmailCalls)
        }

    @Test
    fun `an already-verified account returns 409`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    resendVerificationEmailResult =
                        AppResult.Error(VerifyEmailError.ALREADY_VERIFIED)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = client.post(AuthPaths.RESEND_VERIFICATION) { bearerAuth(testAccessTokenFor(userId = 7)) }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
}
