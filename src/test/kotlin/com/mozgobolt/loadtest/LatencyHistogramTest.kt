/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LatencyHistogramTest {
    @Test
    fun `an empty histogram reports zero`() {
        val histogram = LatencyHistogram()

        assertEquals(Duration.ZERO, histogram.percentile(0.99))
        assertEquals(0L, histogram.summary().count)
    }

    @Test
    fun `percentiles are within the bucket precision of the exact answer`() {
        val histogram = LatencyHistogram()
        (1..1_000).forEach { histogram.record(it.milliseconds) }

        assertEquals(500.0, histogram.percentile(0.50).inWholeMicroseconds / 1_000.0, absoluteTolerance = 500 * 0.025)
        assertEquals(950.0, histogram.percentile(0.95).inWholeMicroseconds / 1_000.0, absoluteTolerance = 950 * 0.025)
        assertEquals(990.0, histogram.percentile(0.99).inWholeMicroseconds / 1_000.0, absoluteTolerance = 990 * 0.025)
    }

    @Test
    fun `the maximum is exact and a single sample is never over-reported`() {
        val histogram = LatencyHistogram()
        histogram.record(1_234.milliseconds)
        histogram.record(2.seconds)

        assertEquals(2_000.0, histogram.summary().maxMs)
        assertEquals(2_000.0, histogram.summary().p99Ms)
        assertEquals(2L, histogram.count)
    }
}
