/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The [index]th tick of a [fixedRate] schedule and how long after its intended time it actually fired. */
internal data class Tick(
    val index: Int,
    val lateness: Duration,
)

/**
 * Ticks at `firstDue + period * index` measured from collection start, never from the previous tick:
 * a slow downstream makes later ticks fire immediately to catch up instead of silently lowering the
 * offered rate (the coordinated-omission trap). [Tick.lateness] exposes how far behind the schedule ran.
 */
internal fun fixedRate(
    period: Duration,
    firstDue: Duration = period,
): Flow<Tick> =
    flow {
        val epoch = TimeSource.Monotonic.markNow()
        generateSequence(0) { it + 1 }.forEach { index ->
            val due = firstDue + period * index
            delay(due - epoch.elapsedNow())
            emit(Tick(index, lateness = (epoch.elapsedNow() - due).coerceAtLeast(Duration.ZERO)))
        }
    }
