/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
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
    fun `a retry with the same idempotency key replays the resend for the same user`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installAuthRoutesTestApp(userService, idempotencyStore) }
            val token = testAccessTokenFor(userId = 7)

            val first =
                client.post(AuthPaths.RESEND_VERIFICATION) {
                    bearerAuth(token)
                    header("Idempotency-Key", "resend-1")
                }
            val replay =
                client.post(AuthPaths.RESEND_VERIFICATION) {
                    bearerAuth(token)
                    header("Idempotency-Key", "resend-1")
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["X-Idempotency-Replayed"])
            assertEquals(listOf(7), userService.resendVerificationEmailCalls)
        }

    @Test
    fun `a resend idempotency key cannot be reused by another user`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installAuthRoutesTestApp(userService, idempotencyStore) }

            val first =
                client.post(AuthPaths.RESEND_VERIFICATION) {
                    bearerAuth(testAccessTokenFor(userId = 7))
                    header("Idempotency-Key", "resend-2")
                }
            val conflict =
                client.post(AuthPaths.RESEND_VERIFICATION) {
                    bearerAuth(testAccessTokenFor(userId = 8))
                    header("Idempotency-Key", "resend-2")
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, conflict.status)
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
