package com.shelflife.core.modules.plugin

import com.shelflife.core.data.email.PermanentEmailDeliveryException
import com.shelflife.core.data.email.TransientEmailDeliveryException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests read the shared [ResilienceRegistry] singleton's *configuration* only (never
 * executing calls through it — that would leave retry/circuit-breaker metrics polluted for every
 * other test in the same JVM run for the rest of the suite). Behavioral tests instead build a
 * throwaway [Retry]/[CircuitBreaker] from that same config, so the assertions still cover the
 * real configured behavior without any cross-test state leakage.
 */
class ResilienceConfigTest {
    @Test
    fun `emailRetry is configured for three attempts with exponential backoff`() {
        val config = ResilienceRegistry.emailRetry.retryConfig

        assertEquals(3, config.maxAttempts)
    }

    @Test
    fun `emailRetry only retries transient email delivery failures`() {
        val predicate = ResilienceRegistry.emailRetry.retryConfig.exceptionPredicate

        assertTrue(predicate.test(TransientEmailDeliveryException("boom", null)))
        assertFalse(predicate.test(PermanentEmailDeliveryException("boom", null)))
        assertFalse(predicate.test(RuntimeException("unrelated failure")))
    }

    @Test
    fun `emailCircuitBreaker is configured with the documented thresholds`() {
        val config = ResilienceRegistry.emailCircuitBreaker.circuitBreakerConfig

        assertEquals(50f, config.failureRateThreshold)
        assertEquals(50f, config.slowCallRateThreshold)
        assertEquals(10, config.minimumNumberOfCalls)
        assertEquals(20, config.slidingWindowSize)
        assertEquals(5, config.slowCallDurationThreshold.seconds)
        // waitDurationInOpenState is expressed as an interval function in modern resilience4j —
        // applying it for the first open-state attempt should yield the documented 30s.
        assertEquals(30_000L, config.waitIntervalFunctionInOpenState.apply(1))
    }

    @Test
    fun `a transient failure is retried up to the configured attempt count, then gives up`() {
        runBlocking {
            val retry = Retry.of("test-transient", ResilienceRegistry.emailRetry.retryConfig)
            var attempts = 0

            assertFailsWith<TransientEmailDeliveryException> {
                retry.executeSuspendFunction {
                    attempts++
                    throw TransientEmailDeliveryException("always fails", null)
                }
            }

            assertEquals(3, attempts, "expected exactly maxAttempts calls before giving up")
        }
    }

    @Test
    fun `a permanent failure is not retried at all`() {
        runBlocking {
            val retry = Retry.of("test-permanent", ResilienceRegistry.emailRetry.retryConfig)
            var attempts = 0

            assertFailsWith<PermanentEmailDeliveryException> {
                retry.executeSuspendFunction {
                    attempts++
                    throw PermanentEmailDeliveryException("never retried", null)
                }
            }

            assertEquals(1, attempts, "a non-retryable exception must fail on the first attempt")
        }
    }

    @Test
    fun `a transient failure that eventually succeeds does not exhaust all retries needlessly`() {
        runBlocking {
            val retry = Retry.of("test-eventual-success", ResilienceRegistry.emailRetry.retryConfig)
            var attempts = 0

            val result =
                retry.executeSuspendFunction {
                    attempts++
                    if (attempts < 2) throw TransientEmailDeliveryException("flaky", null)
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(2, attempts)
        }
    }

    @Test
    fun `the circuit opens once the failure-rate threshold is reached and rejects further calls`() {
        runBlocking {
            val breaker = CircuitBreaker.of("test-breaker", ResilienceRegistry.emailCircuitBreaker.circuitBreakerConfig)

            // minimumNumberOfCalls = 10, failureRateThreshold = 50% — ten straight failures must
            // be enough evidence to trip the breaker open.
            repeat(10) {
                assertFailsWith<TransientEmailDeliveryException> {
                    breaker.executeSuspendFunction {
                        throw TransientEmailDeliveryException("boom", null)
                    }
                }
            }

            assertEquals(State.OPEN, breaker.state)

            var calledWhileOpen = false
            assertFailsWith<CallNotPermittedException> {
                breaker.executeSuspendFunction {
                    calledWhileOpen = true
                    "should never run"
                }
            }
            assertFalse(calledWhileOpen, "the wrapped block must not run at all while the circuit is open")
        }
    }

    @Test
    fun `a policy without a retry just runs the block through the circuit breaker`() {
        runBlocking {
            val policy =
                ResiliencePolicy(
                    CircuitBreaker.of("test-no-retry", ResilienceRegistry.s3CircuitBreaker.circuitBreakerConfig),
                )
            var calls = 0

            assertFailsWith<TransientEmailDeliveryException> {
                policy.execute {
                    calls++
                    throw TransientEmailDeliveryException("boom", null)
                }
            }

            assertEquals(1, calls, "with no retry configured, a single failure must not be retried at all")
        }
    }

    @Test
    fun `a policy's retry loop counts as one outcome for its circuit breaker, not one per attempt`() {
        runBlocking {
            val policy =
                ResiliencePolicy(
                    circuitBreaker =
                        CircuitBreaker.of(
                            "test-nesting-breaker",
                            ResilienceRegistry.emailCircuitBreaker.circuitBreakerConfig,
                        ),
                    retry = Retry.of("test-nesting-retry", ResilienceRegistry.emailRetry.retryConfig),
                )

            repeat(4) {
                assertFailsWith<TransientEmailDeliveryException> {
                    policy.execute<Unit> { throw TransientEmailDeliveryException("boom", null) }
                }
            }

            assertEquals(State.CLOSED, policy.circuitBreaker.state)
        }
    }
}
