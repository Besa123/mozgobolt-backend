/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import com.mozgobolt.core.data.security.TransientPasswordBreachException
import java.time.Duration

private const val PASSWORD_BREACH_MAX_CONCURRENT_CALLS = 4
private const val PASSWORD_BREACH_MINIMUM_NUMBER_OF_CALLS = 5
private const val PASSWORD_BREACH_CIRCUIT_BREAKER_WINDOW_SIZE = 10
private const val PASSWORD_BREACH_SLOW_CALL_THRESHOLD_SECONDS = 2L
private const val PASSWORD_BREACH_MAX_ATTEMPTS = 2
private const val PASSWORD_BREACH_INITIAL_BACKOFF_MILLIS = 100L
private const val PASSWORD_BREACH_DEADLINE_SECONDS = 3L

internal fun createPasswordBreachResiliencePolicy(): ResiliencePolicy =
    ResiliencePolicy(
        circuitBreaker =
            createCircuitBreaker(
                name = "passwordBreachCircuitBreaker",
                minimumNumberOfCalls = PASSWORD_BREACH_MINIMUM_NUMBER_OF_CALLS,
                slidingWindowSize = PASSWORD_BREACH_CIRCUIT_BREAKER_WINDOW_SIZE,
                slowCallThreshold = Duration.ofSeconds(PASSWORD_BREACH_SLOW_CALL_THRESHOLD_SECONDS),
            ),
        retry =
            createRetry(
                name = "passwordBreachRetry",
                maxAttempts = PASSWORD_BREACH_MAX_ATTEMPTS,
                initialBackoff = Duration.ofMillis(PASSWORD_BREACH_INITIAL_BACKOFF_MILLIS),
                shouldRetry = { it is TransientPasswordBreachException },
            ),
        bulkhead = createBulkhead("passwordBreachBulkhead", PASSWORD_BREACH_MAX_CONCURRENT_CALLS),
        timeLimiter =
            createTimeLimiter(
                "passwordBreachTimeLimiter",
                Duration.ofSeconds(PASSWORD_BREACH_DEADLINE_SECONDS),
            ),
    )
