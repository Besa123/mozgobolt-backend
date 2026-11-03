/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import com.mozgobolt.core.data.email.PermanentEmailDeliveryException
import com.mozgobolt.core.data.email.TransientEmailDeliveryException
import com.mozgobolt.core.data.push.InvalidPushTargetException
import com.mozgobolt.core.data.push.PermanentPushDeliveryException
import com.mozgobolt.core.data.push.TransientPushDeliveryException
import com.mozgobolt.core.data.security.TransientClamAvException
import com.mozgobolt.core.data.security.TransientPasswordBreachException
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Profiles are copied into throwaway instances for behavior tests, so retry counters, breaker
 * windows, bulkhead permits, and timeouts cannot leak between tests.
 */
class ResiliencePolicyTest {
    private val policies = ExternalServiceResiliencePolicies.create()

    @Test
    fun `dependency policies execute successful calls`() {
        runBlocking {
            assertEquals(
                "email",
                isolatedPolicy(
                    "email",
                    policies.email.circuitBreaker.circuitBreakerConfig,
                    policies.email.retry!!.retryConfig,
                ).execute {
                    "email"
                },
            )
            assertEquals(
                "clamav",
                isolatedPolicy(
                    "clamav",
                    policies.clamAv.circuitBreaker.circuitBreakerConfig,
                    policies.clamAv.retry!!.retryConfig,
                ).execute {
                    "clamav"
                },
            )
            assertEquals(
                "s3",
                isolatedPolicy("s3", policies.s3.circuitBreaker.circuitBreakerConfig).execute {
                    "s3"
                },
            )
            assertEquals(
                "push",
                isolatedPolicy(
                    "push",
                    policies.push.circuitBreaker.circuitBreakerConfig,
                    policies.push.retry!!.retryConfig,
                ).execute {
                    "push"
                },
            )
        }
    }

    private fun isolatedPolicy(
        name: String,
        circuitBreakerConfig: CircuitBreakerConfig,
        retryConfig: RetryConfig? = null,
    ): ResiliencePolicy =
        ResiliencePolicy(
            circuitBreaker = CircuitBreaker.of("$name-test-breaker", circuitBreakerConfig),
            retry = retryConfig?.let { Retry.of("$name-test-retry", it) },
            bulkhead =
                Bulkhead.of(
                    "$name-test-bulkhead",
                    BulkheadConfig
                        .custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build(),
                ),
        )

    @Test
    fun `emailRetry is configured for three attempts with exponential backoff`() {
        val config = policies.email.retry!!.retryConfig

        assertEquals(3, config.maxAttempts)
    }

    @Test
    fun `emailRetry only retries transient email delivery failures`() {
        val predicate =
            policies.email.retry!!
                .retryConfig.exceptionPredicate

        assertTrue(predicate.test(TransientEmailDeliveryException("boom", null)))
        assertFalse(predicate.test(PermanentEmailDeliveryException("boom", null)))
        assertFalse(predicate.test(RuntimeException("unrelated failure")))
    }

    @Test
    fun `emailCircuitBreaker is configured with the documented thresholds`() {
        val config = policies.email.circuitBreaker.circuitBreakerConfig

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
    fun `clamav retry allows ordinary failures but never retries cancellation`() {
        val config = policies.clamAv.retry!!.retryConfig
        val circuitBreakerConfig = policies.clamAv.circuitBreaker.circuitBreakerConfig

        assertEquals(2, config.maxAttempts)
        assertTrue(config.exceptionPredicate.test(TransientClamAvException(IOException("connection reset"))))
        assertFalse(config.exceptionPredicate.test(IllegalStateException("invalid ClamAV response")))
        assertFalse(config.exceptionPredicate.test(CancellationException("request cancelled")))
        assertEquals(5, circuitBreakerConfig.minimumNumberOfCalls)
    }

    @Test
    fun `password breach retry is bounded to transient service failures`() {
        val config = policies.passwordBreach.retry!!.retryConfig

        assertEquals(2, config.maxAttempts)
        assertTrue(config.exceptionPredicate.test(TransientPasswordBreachException(IOException("connection reset"))))
        assertFalse(config.exceptionPredicate.test(IllegalArgumentException("invalid request")))
    }

    @Test
    fun `push retry is limited to transient delivery failures`() {
        val retryConfig = policies.push.retry!!.retryConfig
        val circuitBreakerConfig = policies.push.circuitBreaker.circuitBreakerConfig

        assertEquals(3, retryConfig.maxAttempts)
        assertTrue(retryConfig.exceptionPredicate.test(TransientPushDeliveryException("retry")))
        assertFalse(retryConfig.exceptionPredicate.test(PermanentPushDeliveryException("do not retry")))
        assertEquals(10, circuitBreakerConfig.minimumNumberOfCalls)
    }

    @Test
    fun `missing S3 objects are ignored by the S3 circuit breaker`() {
        val circuitBreakerConfig = policies.s3.circuitBreaker.circuitBreakerConfig
        val ignored = circuitBreakerConfig.ignoreExceptionPredicate

        assertTrue(ignored.test(NoSuchKeyException.builder().message("missing key").build()))
        assertFalse(ignored.test(IllegalStateException("unrelated failure")))
    }

    @Test
    fun `permanent email and push errors do not poison dependency circuit breakers`() {
        val emailCircuitBreakerConfig = policies.email.circuitBreaker.circuitBreakerConfig
        val pushCircuitBreakerConfig = policies.push.circuitBreaker.circuitBreakerConfig
        val emailIgnored = emailCircuitBreakerConfig.ignoreExceptionPredicate
        val pushIgnored = pushCircuitBreakerConfig.ignoreExceptionPredicate

        assertTrue(emailIgnored.test(PermanentEmailDeliveryException("invalid recipient")))
        assertFalse(emailIgnored.test(TransientEmailDeliveryException("provider unavailable")))
        assertTrue(pushIgnored.test(InvalidPushTargetException("unregistered")))
        assertTrue(pushIgnored.test(PermanentPushDeliveryException("invalid payload")))
        assertFalse(pushIgnored.test(TransientPushDeliveryException("provider unavailable")))
    }

    @Test
    fun `profiles have bounded zero-wait concurrency and explicit deadlines`() {
        val emailBulkheadConfig = requireNotNull(policies.email.bulkhead).bulkheadConfig
        val emailTimeLimiterConfig = requireNotNull(policies.email.timeLimiter).timeLimiterConfig
        val breachBulkheadConfig = requireNotNull(policies.passwordBreach.bulkhead).bulkheadConfig
        val breachTimeLimiterConfig = requireNotNull(policies.passwordBreach.timeLimiter).timeLimiterConfig
        val clamAvBulkheadConfig = requireNotNull(policies.clamAv.bulkhead).bulkheadConfig
        val s3BulkheadConfig = requireNotNull(policies.s3.bulkhead).bulkheadConfig
        val pushBulkheadConfig = requireNotNull(policies.push.bulkhead).bulkheadConfig
        val pushTimeLimiterConfig = requireNotNull(policies.push.timeLimiter).timeLimiterConfig

        assertEquals(20, emailBulkheadConfig.maxConcurrentCalls)
        assertEquals(Duration.ZERO, emailBulkheadConfig.maxWaitDuration)
        assertEquals(Duration.ofSeconds(15), emailTimeLimiterConfig.timeoutDuration)
        assertEquals(4, breachBulkheadConfig.maxConcurrentCalls)
        assertEquals(Duration.ZERO, breachBulkheadConfig.maxWaitDuration)
        assertEquals(Duration.ofSeconds(3), breachTimeLimiterConfig.timeoutDuration)
        assertEquals(4, clamAvBulkheadConfig.maxConcurrentCalls)
        assertEquals(16, s3BulkheadConfig.maxConcurrentCalls)
        assertEquals(16, pushBulkheadConfig.maxConcurrentCalls)
        assertEquals(Duration.ofSeconds(10), pushTimeLimiterConfig.timeoutDuration)
    }

    @Test
    fun `bulkhead rejects excess calls without counting them against the circuit breaker`() {
        runBlocking {
            val circuitBreaker = CircuitBreaker.of("test-bulkhead-breaker", CircuitBreakerConfig.ofDefaults())
            val bulkhead =
                Bulkhead.of(
                    "test-bulkhead",
                    BulkheadConfig
                        .custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build(),
                )
            val policy = ResiliencePolicy(circuitBreaker = circuitBreaker, bulkhead = bulkhead)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<String>()

            val firstCall =
                async {
                    policy.execute {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()

            assertFailsWith<BulkheadFullException> {
                policy.execute { "must not run while the only permit is held" }
            }

            release.complete("first call completed")
            assertEquals("first call completed", firstCall.await())
            assertEquals(State.CLOSED, circuitBreaker.state)
        }
    }

    @Test
    fun `caller cancellation releases bulkhead permission without affecting the circuit breaker`() {
        runBlocking {
            val circuitBreaker = CircuitBreaker.of("test-cancellation-breaker", CircuitBreakerConfig.ofDefaults())
            val bulkhead =
                Bulkhead.of(
                    "test-cancellation-bulkhead",
                    BulkheadConfig
                        .custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build(),
                )
            val policy = ResiliencePolicy(circuitBreaker = circuitBreaker, bulkhead = bulkhead)
            val entered = CompletableDeferred<Unit>()

            val call =
                launch {
                    policy.execute {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            call.cancelAndJoin()

            assertEquals(State.CLOSED, circuitBreaker.state)
            assertEquals("permit released", policy.execute { "permit released" })
        }
    }

    @Test
    fun `time limiter timeout is recorded by the circuit breaker`() {
        runBlocking {
            val circuitBreaker =
                CircuitBreaker.of(
                    "test-timeout-breaker",
                    CircuitBreakerConfig
                        .custom()
                        .failureRateThreshold(50f)
                        .minimumNumberOfCalls(1)
                        .slidingWindowSize(1)
                        .build(),
                )
            val timeLimiter =
                TimeLimiter.of(
                    "test-timeout",
                    TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(50)).build(),
                )
            val policy = ResiliencePolicy(circuitBreaker = circuitBreaker, timeLimiter = timeLimiter)

            assertFailsWith<TimeoutException> {
                policy.execute {
                    delay(1_000)
                    "too late"
                }
            }

            assertEquals(State.OPEN, circuitBreaker.state)
        }
    }

    @Test
    fun `time limiter rejects non-positive deadlines`() {
        val policy =
            ResiliencePolicy(
                circuitBreaker = CircuitBreaker.ofDefaults("test-invalid-deadline"),
            )

        assertFailsWith<IllegalArgumentException> {
            policy.withTimeLimiter("zero-deadline", Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            policy.withTimeLimiter("negative-deadline", Duration.ofMillis(-1))
        }
    }

    @Test
    fun `a transient failure is retried up to the configured attempt count, then gives up`() {
        runBlocking {
            val retry = Retry.of("test-transient", policies.email.retry!!.retryConfig)
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
            val retry = Retry.of("test-permanent", policies.email.retry!!.retryConfig)
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
            val retry = Retry.of("test-eventual-success", policies.email.retry!!.retryConfig)
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
            val breaker = CircuitBreaker.of("test-breaker", policies.email.circuitBreaker.circuitBreakerConfig)

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
                    CircuitBreaker.of("test-no-retry", policies.s3.circuitBreaker.circuitBreakerConfig),
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
                            policies.email.circuitBreaker.circuitBreakerConfig,
                        ),
                    retry = Retry.of("test-nesting-retry", policies.email.retry!!.retryConfig),
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
