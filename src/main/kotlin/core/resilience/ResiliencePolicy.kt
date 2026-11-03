/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.kotlin.bulkhead.executeSuspendFunction
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.kotlin.timelimiter.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.timelimiter.TimeLimiter
import java.time.Duration

/**
 * Applies one dependency's resilience controls in a deliberate order:
 * bulkhead -> circuit breaker -> total deadline -> retry -> dependency call.
 *
 * A saturated bulkhead fails before affecting breaker statistics. The breaker measures one
 * logical call, including its retries. The deadline bounds the complete retry sequence.
 */
class ResiliencePolicy(
    /** Circuit breaker that tracks failures for this dependency. */
    val circuitBreaker: CircuitBreaker,
    /** Optional retry policy applied within the total deadline. */
    val retry: Retry? = null,
    /** Optional concurrency limit applied before the circuit breaker. */
    val bulkhead: Bulkhead? = null,
    /** Optional deadline covering the complete retry sequence. */
    val timeLimiter: TimeLimiter? = null,
) {
    /** Returns a copy of this policy with a newly configured total-call deadline. */
    fun withTimeLimiter(
        name: String,
        timeout: Duration,
    ): ResiliencePolicy =
        ResiliencePolicy(
            circuitBreaker = circuitBreaker,
            retry = retry,
            bulkhead = bulkhead,
            timeLimiter = createTimeLimiter(name, timeout),
        )

    /** Executes [block] under the configured concurrency, circuit, deadline, and retry controls. */
    suspend fun <T> execute(block: suspend () -> T): T {
        val retrying: suspend () -> T = {
            retry?.executeSuspendFunction(block) ?: block()
        }
        val timeLimited: suspend () -> T = {
            timeLimiter?.executeSuspendFunction(retrying) ?: retrying()
        }
        val circuitProtected: suspend () -> T = {
            circuitBreaker.executeSuspendFunction(timeLimited)
        }

        return bulkhead?.executeSuspendFunction(circuitProtected) ?: circuitProtected()
    }
}
