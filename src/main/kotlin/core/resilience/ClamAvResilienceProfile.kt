/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import com.mozgobolt.core.data.security.TransientClamAvException
import java.time.Duration

private const val CLAM_AV_MAX_CONCURRENT_CALLS = 4
private const val CLAM_AV_MINIMUM_NUMBER_OF_CALLS = 5
private const val CLAM_AV_CIRCUIT_BREAKER_WINDOW_SIZE = 10
private const val CLAM_AV_SLOW_CALL_THRESHOLD_SECONDS = 3L
private const val CLAM_AV_MAX_ATTEMPTS = 2
private const val CLAM_AV_INITIAL_BACKOFF_MILLIS = 50L

internal fun createClamAvResiliencePolicy(): ResiliencePolicy =
    ResiliencePolicy(
        circuitBreaker =
            createCircuitBreaker(
                name = "clamAvCircuitBreaker",
                minimumNumberOfCalls = CLAM_AV_MINIMUM_NUMBER_OF_CALLS,
                slidingWindowSize = CLAM_AV_CIRCUIT_BREAKER_WINDOW_SIZE,
                slowCallThreshold = Duration.ofSeconds(CLAM_AV_SLOW_CALL_THRESHOLD_SECONDS),
            ),
        retry =
            createRetry(
                name = "clamAvRetry",
                maxAttempts = CLAM_AV_MAX_ATTEMPTS,
                initialBackoff = Duration.ofMillis(CLAM_AV_INITIAL_BACKOFF_MILLIS),
                shouldRetry = { it is TransientClamAvException },
            ),
        bulkhead = createBulkhead("clamAvBulkhead", CLAM_AV_MAX_CONCURRENT_CALLS),
    )
