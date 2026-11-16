/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class FixedRateTest {
    @Test
    fun `ticks are numbered from zero and never early`() =
        runBlocking {
            val started = TimeSource.Monotonic.markNow()

            val ticks = fixedRate(40.milliseconds).take(4).toList()

            assertEquals(listOf(0, 1, 2, 3), ticks.map(Tick::index))
            assertTrue(started.elapsedNow() >= 160.milliseconds, "four ticks of 40ms cannot finish sooner than 160ms")
        }

    @Test
    fun `a slow consumer makes later ticks fire late instead of lowering the offered rate`() =
        runBlocking {
            val started = TimeSource.Monotonic.markNow()

            val ticks =
                fixedRate(30.milliseconds)
                    .onEach { delay(90.milliseconds) }
                    .take(3)
                    .toList()

            assertTrue(
                ticks.last().lateness >= 100.milliseconds,
                "the third tick was due at 90ms but ran ~180ms: ${ticks.last()}",
            )
            assertTrue(
                started.elapsedNow() < 400.milliseconds,
                "the schedule must not stretch to period + work per tick",
            )
        }
}
