/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import com.mozgobolt.core.data.email.PermanentEmailDeliveryException
import com.mozgobolt.core.data.email.TransientEmailDeliveryException
import java.time.Duration

private const val EMAIL_MAX_CONCURRENT_CALLS = 20
private const val EMAIL_MINIMUM_NUMBER_OF_CALLS = 10
private const val EMAIL_CIRCUIT_BREAKER_WINDOW_SIZE = 20
private const val EMAIL_SLOW_CALL_THRESHOLD_SECONDS = 5L
private const val EMAIL_MAX_ATTEMPTS = 3
private const val EMAIL_INITIAL_BACKOFF_MILLIS = 100L
private const val EMAIL_DEADLINE_SECONDS = 15L

internal fun createEmailResiliencePolicy(): ResiliencePolicy =
    ResiliencePolicy(
        circuitBreaker =
            createCircuitBreaker(
                name = "emailCircuitBreaker",
                minimumNumberOfCalls = EMAIL_MINIMUM_NUMBER_OF_CALLS,
                slidingWindowSize = EMAIL_CIRCUIT_BREAKER_WINDOW_SIZE,
                slowCallThreshold = Duration.ofSeconds(EMAIL_SLOW_CALL_THRESHOLD_SECONDS),
                shouldIgnoreException = { it is PermanentEmailDeliveryException },
            ),
        retry =
            createRetry(
                name = "emailRetry",
                maxAttempts = EMAIL_MAX_ATTEMPTS,
                initialBackoff = Duration.ofMillis(EMAIL_INITIAL_BACKOFF_MILLIS),
                shouldRetry = { it is TransientEmailDeliveryException },
            ),
        // Match the outbox worker's batch size so a claimed batch isn't needlessly rejected.
        bulkhead = createBulkhead("emailBulkhead", EMAIL_MAX_CONCURRENT_CALLS),
        timeLimiter = createTimeLimiter("emailTimeLimiter", Duration.ofSeconds(EMAIL_DEADLINE_SECONDS)),
    )
