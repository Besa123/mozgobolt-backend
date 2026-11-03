/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.time.Duration

private const val S3_MAX_CONCURRENT_CALLS = 16
private const val S3_MINIMUM_NUMBER_OF_CALLS = 10
private const val S3_CIRCUIT_BREAKER_WINDOW_SIZE = 20
private const val S3_SLOW_CALL_THRESHOLD_SECONDS = 5L

internal fun createS3ResiliencePolicy(): ResiliencePolicy =
    ResiliencePolicy(
        circuitBreaker =
            createCircuitBreaker(
                name = "s3CircuitBreaker",
                minimumNumberOfCalls = S3_MINIMUM_NUMBER_OF_CALLS,
                slidingWindowSize = S3_CIRCUIT_BREAKER_WINDOW_SIZE,
                slowCallThreshold = Duration.ofSeconds(S3_SLOW_CALL_THRESHOLD_SECONDS),
                shouldIgnoreException = { it is NoSuchKeyException },
            ),
        bulkhead = createBulkhead("s3Bulkhead", S3_MAX_CONCURRENT_CALLS),
    )
