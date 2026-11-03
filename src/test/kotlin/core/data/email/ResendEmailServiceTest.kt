/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.email

import com.mozgobolt.core.modules.AppConfig
import com.resend.core.exception.ResendException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResendEmailServiceTest {
    @Test
    fun `verification email uses configured sender recipient link and expiration`() {
        runBlocking {
            val sent = mutableListOf<OutgoingEmail>()
            val service =
                ResendEmailService(
                    testConfig(),
                    EmailSender { email ->
                        sent += email
                        "message-id"
                    },
                )

            service.sendVerificationEmail("recipient@example.test", "verification-token")

            val message = sent.single()
            assertEquals("noreply@example.test", message.fromAddress)
            assertEquals("recipient@example.test", message.recipient)
            assertEquals("Verify your email address", message.subject)
            assertTrue(
                message.html.contains(
                    "https://api.example.test/api/v1/auth/verify-email?token=verification-token",
                ),
            )
            assertTrue(message.html.contains("This link expires in 36 hours."))
        }
    }

    @Test
    fun `password reset email uses configured sender recipient link and expiration`() {
        runBlocking {
            val sent = mutableListOf<OutgoingEmail>()
            val service =
                ResendEmailService(
                    testConfig(),
                    EmailSender { email ->
                        sent += email
                        "message-id"
                    },
                )

            service.sendPasswordResetEmail("recipient@example.test", "reset-token")

            val message = sent.single()
            assertEquals("noreply@example.test", message.fromAddress)
            assertEquals("recipient@example.test", message.recipient)
            assertEquals("Reset your password", message.subject)
            assertTrue(
                message.html.contains("https://api.example.test/reset#token=reset-token"),
            )
            assertTrue(message.html.contains("This link expires in 42 minutes."))
        }
    }

    @Test
    fun `provider idempotency keys are stable per email intent and do not expose bearer tokens`() {
        runBlocking {
            val sent = mutableListOf<OutgoingEmail>()
            val service =
                ResendEmailService(
                    testConfig(),
                    EmailSender { email ->
                        sent += email
                        "message-id"
                    },
                )
            val token = "same-one-time-token"

            service.sendVerificationEmail("recipient@example.test", token)
            service.sendVerificationEmail("recipient@example.test", token)
            service.sendPasswordResetEmail("recipient@example.test", token)
            service.sendVerificationEmail("other@example.test", token)

            val verificationKey = sent[0].idempotencyKey
            assertEquals(verificationKey, sent[1].idempotencyKey)
            assertNotEquals(verificationKey, sent[2].idempotencyKey)
            assertNotEquals(verificationKey, sent[3].idempotencyKey)
            assertFalse(verificationKey.contains(token))
        }
    }

    @Test
    fun `transient delivery failure is mapped without exposing the recipient`() {
        runBlocking {
            val recipient = "private-recipient@example.test"
            val failure = ResendException(429, "Too Many Requests")
            val service =
                ResendEmailService(
                    testConfig(),
                    EmailSender { throw failure.toEmailDeliveryException() },
                )

            val result =
                assertFailsWith<TransientEmailDeliveryException> {
                    service.sendVerificationEmail(recipient, "verification-token")
                }

            assertEquals("Resend API returned 429", result.message)
            assertFalse(result.message.orEmpty().contains(recipient))
        }
    }

    @Test
    fun `cancellation from the sender is not converted into a delivery failure`() {
        runBlocking {
            val cancellation = kotlinx.coroutines.CancellationException("cancelled")
            val service =
                ResendEmailService(
                    testConfig(),
                    EmailSender { throw cancellation },
                )

            val thrown =
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    service.sendPasswordResetEmail("recipient@example.test", "reset-token")
                }

            assertEquals(cancellation.message, thrown.message)
        }
    }

    @Test
    fun `a network-level IOException is classified as transient`() {
        val networkFailure = RuntimeException("connection reset", IOException("reset by peer"))

        val result = networkFailure.toEmailDeliveryException()

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 429 rate-limit response is classified as transient`() {
        val rateLimited = ResendException(429, "Too Many Requests")

        val result = rateLimited.toEmailDeliveryException()

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 500 server error is classified as transient`() {
        val serverError = ResendException(500, "Internal Server Error")

        val result = serverError.toEmailDeliveryException()

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 503 server error is classified as transient`() {
        val serverError = ResendException(503, "Service Unavailable")

        val result = serverError.toEmailDeliveryException()

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 400 bad request is classified as permanent`() {
        val badRequest = ResendException(400, "Bad Request")

        val result = badRequest.toEmailDeliveryException()

        assertIs<PermanentEmailDeliveryException>(result)
    }

    @Test
    fun `a 401 unauthorized (bad API key) is classified as permanent`() {
        val unauthorized = ResendException(401, "Unauthorized")

        val result = unauthorized.toEmailDeliveryException()

        assertIs<PermanentEmailDeliveryException>(result)
    }

    @Test
    fun `a 422 unprocessable recipient is classified as permanent`() {
        val unprocessable = ResendException(422, "Unprocessable Entity")

        val result = unprocessable.toEmailDeliveryException()

        assertIs<PermanentEmailDeliveryException>(result)
    }

    @Test
    fun `an unrecognized failure shape is not retried as transient`() {
        val unknown = RuntimeException("something went wrong")

        val result = unknown.toEmailDeliveryException()

        assertIs<PermanentEmailDeliveryException>(result)
    }

    @Test
    fun `a ResendException with no status code defaults to transient`() {
        val unknown = ResendException("malformed response, no status code available")

        val result = unknown.toEmailDeliveryException()

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `the original exception is preserved as the cause`() {
        val original = ResendException(500, "Internal Server Error")

        val result = original.toEmailDeliveryException()

        assertSame(original, result.cause)
    }

    private fun testConfig() =
        AppConfig(
            baseUrl = "https://api.example.test",
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = "unused", issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = "unused"),
            cors = AppConfig.Cors(),
            email =
                AppConfig.Email(
                    fromAddress = "noreply@example.test",
                    verificationTokenExpirationHours = 36,
                    resetTokenExpirationMinutes = 42,
                ),
        )
}
