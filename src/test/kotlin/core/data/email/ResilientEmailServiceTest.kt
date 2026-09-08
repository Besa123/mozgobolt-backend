package com.shelflife.core.data.email

import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.modules.plugin.ResiliencePolicy
import com.shelflife.core.modules.plugin.ResilienceRegistry
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResilientEmailServiceTest {
    private fun freshResilience(): ResiliencePolicy =
        ResiliencePolicy(
            circuitBreaker =
                CircuitBreaker.of(
                    "test-email-breaker",
                    ResilienceRegistry.emailCircuitBreaker.circuitBreakerConfig,
                ),
            retry = Retry.of("test-email-retry", ResilienceRegistry.emailRetry.retryConfig),
        )

    private class FakeEmailService(
        private val failuresBeforeSuccess: Int = 0,
        private val failure: () -> EmailDeliveryException = { TransientEmailDeliveryException("boom", null) },
    ) : EmailService {
        var attempts = 0
            private set

        override suspend fun sendVerificationEmail(
            to: String,
            token: String,
        ) {
            attempts++
            if (attempts <= failuresBeforeSuccess) throw failure()
        }

        override suspend fun sendPasswordResetEmail(
            to: String,
            token: String,
        ) = sendVerificationEmail(to, token)
    }

    @Test
    fun `a transient failure that recovers within maxAttempts succeeds without surfacing an error`() {
        runBlocking {
            val delegate = FakeEmailService(failuresBeforeSuccess = 1)
            val service = ResilientEmailService(delegate, resilience = freshResilience())

            service.sendVerificationEmail("user@example.com", "token")

            assertEquals(2, delegate.attempts, "expected one failure, then a retried success")
        }
    }

    @Test
    fun `a permanent failure is not retried and propagates immediately`() {
        runBlocking {
            val delegate =
                FakeEmailService(
                    failuresBeforeSuccess = Int.MAX_VALUE,
                    failure = { PermanentEmailDeliveryException("rejected", null) },
                )
            val service = ResilientEmailService(delegate, resilience = freshResilience())

            assertFailsWith<PermanentEmailDeliveryException> {
                service.sendVerificationEmail("user@example.com", "token")
            }
            assertEquals(1, delegate.attempts, "a permanent failure must not be retried")
        }
    }

    @Test
    fun `a delegate that always fails eventually trips the circuit breaker for later calls`() {
        runBlocking {
            val delegate = FakeEmailService(failuresBeforeSuccess = Int.MAX_VALUE)
            val service = ResilientEmailService(delegate, resilience = freshResilience())

            repeat(10) {
                assertFailsWith<TransientEmailDeliveryException> {
                    service.sendVerificationEmail("user@example.com", "token")
                }
            }

            assertFailsWith<CallNotPermittedException> {
                service.sendVerificationEmail("user@example.com", "token")
            }
        }
    }
}
