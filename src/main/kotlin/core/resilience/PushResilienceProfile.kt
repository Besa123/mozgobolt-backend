/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import com.mozgobolt.core.data.push.InvalidPushTargetException
import com.mozgobolt.core.data.push.PermanentPushDeliveryException
import com.mozgobolt.core.data.push.TransientPushDeliveryException
import java.time.Duration

private const val PUSH_MAX_CONCURRENT_CALLS = 16
private const val PUSH_MINIMUM_NUMBER_OF_CALLS = 10
private const val PUSH_CIRCUIT_BREAKER_WINDOW_SIZE = 20
private const val PUSH_SLOW_CALL_THRESHOLD_SECONDS = 5L
private const val PUSH_MAX_ATTEMPTS = 3
private const val PUSH_INITIAL_BACKOFF_MILLIS = 100L
private const val PUSH_DEADLINE_SECONDS = 10L

internal fun createPushResiliencePolicy(): ResiliencePolicy =
    ResiliencePolicy(
        circuitBreaker =
            createCircuitBreaker(
                name = "pushCircuitBreaker",
                minimumNumberOfCalls = PUSH_MINIMUM_NUMBER_OF_CALLS,
                slidingWindowSize = PUSH_CIRCUIT_BREAKER_WINDOW_SIZE,
                slowCallThreshold = Duration.ofSeconds(PUSH_SLOW_CALL_THRESHOLD_SECONDS),
                shouldIgnoreException = {
                    it is InvalidPushTargetException || it is PermanentPushDeliveryException
                },
            ),
        retry =
            createRetry(
                name = "pushRetry",
                maxAttempts = PUSH_MAX_ATTEMPTS,
                initialBackoff = Duration.ofMillis(PUSH_INITIAL_BACKOFF_MILLIS),
                shouldRetry = { it is TransientPushDeliveryException },
            ),
        bulkhead = createBulkhead("pushBulkhead", PUSH_MAX_CONCURRENT_CALLS),
        timeLimiter = createTimeLimiter("pushTimeLimiter", Duration.ofSeconds(PUSH_DEADLINE_SECONDS)),
    )
