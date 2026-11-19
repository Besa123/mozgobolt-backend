/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TelemetryScheduleTest {
    @Test
    fun `staggered schedules spread truck starts across one interval`() {
        val schedule = TelemetrySchedule(interval = 5.seconds, pointsPerBatch = 1)

        assertEquals(0.seconds, schedule.startOffset(truckIndex = 0, truckCount = 200))
        assertEquals(2.5.seconds, schedule.startOffset(truckIndex = 100, truckCount = 200))
        assertEquals(4.975.seconds, schedule.startOffset(truckIndex = 199, truckCount = 200))
    }

    @Test
    fun `synchronized schedules start every truck in the same interval`() {
        val schedule = TelemetrySchedule(interval = 1.2.seconds, pointsPerBatch = 1, synchronizeTrucks = true)

        assertEquals(0.seconds, schedule.startOffset(truckIndex = 0, truckCount = 200))
        assertEquals(0.seconds, schedule.startOffset(truckIndex = 199, truckCount = 200))
    }

    @Test
    fun `batch count includes only ticks due by the run deadline`() {
        val schedule = TelemetrySchedule(interval = 5.seconds, pointsPerBatch = 1)

        assertEquals(1, schedule.batchCount(startOffset = 0.seconds, runDuration = 5.seconds))
        assertEquals(0, schedule.batchCount(startOffset = 2.5.seconds, runDuration = 5.seconds))
        assertEquals(2, schedule.batchCount(startOffset = 0.seconds, runDuration = 10.seconds))
    }

    @Test
    fun `start offset rejects invalid fleet indices`() {
        val schedule = TelemetrySchedule(interval = 5.seconds, pointsPerBatch = 1)

        assertFailsWith<IllegalArgumentException> { schedule.startOffset(truckIndex = 0, truckCount = 0) }
        assertFailsWith<IllegalArgumentException> { schedule.startOffset(truckIndex = 2, truckCount = 2) }
    }
}
