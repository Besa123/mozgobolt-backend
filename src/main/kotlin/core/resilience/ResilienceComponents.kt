/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import java.time.Duration

private val logger = KotlinLogging.logger {}

private const val FAILURE_RATE_THRESHOLD = 50f
private const val SLOW_CALL_RATE_THRESHOLD = 50f
private const val CIRCUIT_BREAKER_OPEN_SECONDS = 30L
private const val HALF_OPEN_PROBE_CALLS = 3
private const val RETRY_BACKOFF_MULTIPLIER = 2.0
private const val RETRY_JITTER = 0.5

internal fun createCircuitBreaker(
    name: String,
    minimumNumberOfCalls: Int,
    slidingWindowSize: Int,
    slowCallThreshold: Duration,
    shouldIgnoreException: (Throwable) -> Boolean = { false },
): CircuitBreaker {
    val config =
        CircuitBreakerConfig
            .custom()
            .failureRateThreshold(FAILURE_RATE_THRESHOLD)
            .slowCallRateThreshold(SLOW_CALL_RATE_THRESHOLD)
            .slowCallDurationThreshold(slowCallThreshold)
            .waitDurationInOpenState(Duration.ofSeconds(CIRCUIT_BREAKER_OPEN_SECONDS))
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(slidingWindowSize)
            .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_PROBE_CALLS)

    config.ignoreException(shouldIgnoreException)

    return CircuitBreaker.of(name, config.build()).apply {
        eventPublisher.onStateTransition { event -> logStateTransition(name, event) }
    }
}

@Suppress("CascadingCallWrapping") // KtLint requires builder-chain dots to stay on the preceding line.
internal fun createRetry(
    name: String,
    maxAttempts: Int,
    initialBackoff: Duration,
    shouldRetry: (Throwable) -> Boolean,
): Retry =
    Retry
        .of(
            name,
            RetryConfig
                .custom<Any>()
                .maxAttempts(maxAttempts)
                .intervalFunction(
                    IntervalFunction.ofExponentialRandomBackoff(
                        initialBackoff.toMillis(),
                        RETRY_BACKOFF_MULTIPLIER,
                        RETRY_JITTER,
                    ),
                ).retryOnException(shouldRetry)
                .build(),
        ).apply {
            eventPublisher
                .onRetry { event ->
                    logger.warn {
                        "$name retry #${event.numberOfRetryAttempts} " +
                            "(${event.lastThrowable.javaClass.simpleName})"
                    }
                }.onSuccess { event ->
                    if (event.numberOfRetryAttempts > 0) {
                        logger.info { "$name recovered after ${event.numberOfRetryAttempts} retries" }
                    }
                }
        }

internal fun createBulkhead(
    name: String,
    maxConcurrentCalls: Int,
): Bulkhead =
    Bulkhead.of(
        name,
        BulkheadConfig
            .custom()
            .maxConcurrentCalls(maxConcurrentCalls)
            .maxWaitDuration(Duration.ZERO)
            .build(),
    )

internal fun createTimeLimiter(
    name: String,
    timeout: Duration,
): TimeLimiter {
    require(!timeout.isZero && !timeout.isNegative) { "$name timeout must be positive" }

    return TimeLimiter.of(
        name,
        TimeLimiterConfig
            .custom()
            .timeoutDuration(timeout)
            .build(),
    )
}

private fun logStateTransition(
    dependencyName: String,
    event: CircuitBreakerOnStateTransitionEvent,
) {
    val message = { "$dependencyName circuit-breaker: ${event.stateTransition}" }
    if (event.stateTransition.toState == CircuitBreaker.State.OPEN) {
        logger.error(message)
    } else {
        logger.warn(message)
    }
}
