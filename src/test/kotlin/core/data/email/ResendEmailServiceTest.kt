package com.shelflife.core.data.email

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class ResendEmailServiceTest {
    @Test
    fun `a network-level IOException is classified as transient`() {
        val networkFailure = RuntimeException("connection reset", IOException("reset by peer"))

        val result = networkFailure.toEmailDeliveryException("user@example.com")

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 429 rate-limit response is classified as transient`() {
        val rateLimited = RuntimeException("Failed to send email: 429 Too Many Requests")

        val result = rateLimited.toEmailDeliveryException("user@example.com")

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 500 server error is classified as transient`() {
        val serverError = RuntimeException("Failed to send email: 500 Internal Server Error")

        val result = serverError.toEmailDeliveryException("user@example.com")

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 503 server error is classified as transient`() {
        val serverError = RuntimeException("Failed to send email: 503 Service Unavailable")

        val result = serverError.toEmailDeliveryException("user@example.com")

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `a 400 bad request is classified as permanent`() {
        val badRequest = RuntimeException("Failed to send email: 400 Bad Request")

        val result = badRequest.toEmailDeliveryException("user@example.com")

        assertIs<PermanentEmailDeliveryException>(result)
    }

    @Test
    fun `a 401 unauthorized (bad API key) is classified as permanent`() {
        val unauthorized = RuntimeException("Failed to send email: 401 Unauthorized")

        val result = unauthorized.toEmailDeliveryException("user@example.com")

        assertIs<PermanentEmailDeliveryException>(result)
    }

    @Test
    fun `a 422 unprocessable recipient is classified as permanent`() {
        val unprocessable = RuntimeException("Failed to send email: 422 Unprocessable Entity")

        val result = unprocessable.toEmailDeliveryException("user@example.com")

        assertIs<PermanentEmailDeliveryException>(result)
    }

    @Test
    fun `an unrecognized failure shape defaults to transient`() {
        val unknown = RuntimeException("something went wrong")

        val result = unknown.toEmailDeliveryException("user@example.com")

        assertIs<TransientEmailDeliveryException>(result)
    }

    @Test
    fun `the original exception is preserved as the cause`() {
        val original = RuntimeException("Failed to send email: 500 Internal Server Error")

        val result = original.toEmailDeliveryException("user@example.com")

        assertSame(original, result.cause)
    }
}
