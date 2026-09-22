package com.mozgobolt.core.data.push

import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.core.modules.plugin.ResiliencePolicy
import com.mozgobolt.core.modules.plugin.ResilienceRegistry
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResilientPushNotificationSenderTest {
    private fun freshResilience(): ResiliencePolicy =
        ResiliencePolicy(
            circuitBreaker =
                CircuitBreaker.of(
                    "test-push-breaker",
                    ResilienceRegistry.pushCircuitBreaker.circuitBreakerConfig,
                ),
            retry = Retry.of("test-push-retry", ResilienceRegistry.pushRetry.retryConfig),
        )

    private class FakePushNotificationSenderDelegate(
        private val failuresBeforeSuccess: Int = 0,
        private val failure: () -> PushDeliveryException = { TransientPushDeliveryException("boom") },
    ) : PushNotificationSender {
        var attempts = 0
            private set

        override suspend fun send(
            installationId: String,
            data: Map<String, String>,
        ) {
            attempts++
            if (attempts <= failuresBeforeSuccess) throw failure()
        }
    }

    @Test
    fun `a transient failure that recovers within maxAttempts succeeds without surfacing an error`() {
        runBlocking {
            val delegate = FakePushNotificationSenderDelegate(failuresBeforeSuccess = 1)
            val sender = ResilientPushNotificationSender(delegate, resilience = freshResilience())

            sender.send("fid", mapOf("type" to "PING"))

            assertEquals(2, delegate.attempts, "expected one failure, then a retried success")
        }
    }

    @Test
    fun `an invalid-target failure is not retried and propagates immediately`() {
        runBlocking {
            val delegate =
                FakePushNotificationSenderDelegate(
                    failuresBeforeSuccess = Int.MAX_VALUE,
                    failure = { InvalidPushTargetException("gone") },
                )
            val sender = ResilientPushNotificationSender(delegate, resilience = freshResilience())

            assertFailsWith<InvalidPushTargetException> { sender.send("fid", mapOf("type" to "PING")) }
            assertEquals(1, delegate.attempts, "an invalid-target failure must not be retried")
        }
    }

    @Test
    fun `a permanent failure is not retried and propagates immediately`() {
        runBlocking {
            val delegate =
                FakePushNotificationSenderDelegate(
                    failuresBeforeSuccess = Int.MAX_VALUE,
                    failure = { PermanentPushDeliveryException("rejected") },
                )
            val sender = ResilientPushNotificationSender(delegate, resilience = freshResilience())

            assertFailsWith<PermanentPushDeliveryException> { sender.send("fid", mapOf("type" to "PING")) }
            assertEquals(1, delegate.attempts, "a permanent failure must not be retried")
        }
    }

    @Test
    fun `a delegate that always fails eventually trips the circuit breaker for later calls`() {
        runBlocking {
            val delegate = FakePushNotificationSenderDelegate(failuresBeforeSuccess = Int.MAX_VALUE)
            val sender = ResilientPushNotificationSender(delegate, resilience = freshResilience())

            repeat(10) {
                assertFailsWith<TransientPushDeliveryException> { sender.send("fid", mapOf("type" to "PING")) }
            }

            assertFailsWith<CallNotPermittedException> { sender.send("fid", mapOf("type" to "PING")) }
        }
    }
}
