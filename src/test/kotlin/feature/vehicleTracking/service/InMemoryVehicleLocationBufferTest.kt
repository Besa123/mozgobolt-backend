/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryVehicleLocationBufferTest {
    private fun update(vehicleId: Int) =
        VehicleLocationUpdate(
            vehicleId = vehicleId,
            vendorUserId = vehicleId,
            latitude = 0.0,
            longitude = 0.0,
            recordedAt = Instant.now(),
            cellId = CellId("irrelevant"),
        )

    @Test
    fun `draining an empty buffer returns an empty list, not null or an error`() {
        val buffer = InMemoryVehicleLocationBuffer()

        assertEquals(emptyList(), buffer.drainAll())
    }

    @Test
    fun `everything enqueued is returned by the next drain, in order`() {
        val buffer = InMemoryVehicleLocationBuffer()
        buffer.enqueue(update(1))
        buffer.enqueue(update(2))
        buffer.enqueue(update(3))

        val drained = buffer.drainAll()

        assertEquals(listOf(1, 2, 3), drained.map { it.vehicleId })
        buffer.completeBatch(drained)
    }

    @Test
    fun `draining empties the buffer, so a second immediate drain is empty`() {
        val buffer = InMemoryVehicleLocationBuffer()
        buffer.enqueue(update(1))
        val batch = buffer.drainAll()

        assertEquals(emptyList(), buffer.drainAll())
        buffer.completeBatch(batch)
    }

    @Test
    fun `only what's enqueued after a drain shows up in the next one`() {
        val buffer = InMemoryVehicleLocationBuffer()
        buffer.enqueue(update(1))
        val failedBatch = buffer.drainAll()
        buffer.enqueue(update(2))
        buffer.restoreFailedBatch(failedBatch)

        val retryBatch = buffer.drainAll()

        assertEquals(listOf(1, 2), retryBatch.map { it.vehicleId })
        buffer.completeBatch(retryBatch)
    }

    @Test
    fun `concurrent enqueues from many coroutines all land, none lost`() {
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val concurrentEnqueues = 500

            val accepted =
                (1..concurrentEnqueues)
                    .map { vehicleId -> async(Dispatchers.Default) { buffer.enqueue(update(vehicleId)) } }
                    .awaitAll()

            val drained = buffer.drainAll()
            assertTrue(accepted.all { it })
            assertEquals(concurrentEnqueues, drained.size)
            assertEquals((1..concurrentEnqueues).toSet(), drained.map { it.vehicleId }.toSet())
            buffer.completeBatch(drained)
            assertTrue(buffer.drainAll().isEmpty(), "every enqueued item must have been drained exactly once")
        }
    }

    @Test
    fun `cap includes an in-flight batch and releases capacity after completion`() {
        val buffer = InMemoryVehicleLocationBuffer(maxPendingUpdates = 2)
        val first = update(1)
        val second = update(2)
        val overflow = update(3)

        assertTrue(buffer.enqueue(first))
        assertTrue(buffer.enqueue(second))
        val failedBatch = buffer.drainAll()
        assertFalse(buffer.enqueue(overflow), "claimed rows still occupy buffer capacity")

        buffer.restoreFailedBatch(failedBatch)
        val retryBatch = buffer.drainAll()

        assertEquals(listOf(1, 2), retryBatch.map { it.vehicleId })
        buffer.completeBatch(retryBatch)

        assertTrue(buffer.enqueue(overflow), "persisting a batch releases its capacity")
        val nextBatch = buffer.drainAll()
        assertEquals(listOf(3), nextBatch.map { it.vehicleId })
        buffer.completeBatch(nextBatch)
    }

    @Test
    fun `a full queued buffer preserves its FIFO backlog and rejects only the new history copy`() {
        val buffer = InMemoryVehicleLocationBuffer(maxPendingUpdates = 2)
        val first = update(1)
        val second = update(2)

        assertTrue(buffer.enqueue(first))
        assertTrue(buffer.enqueue(second))
        assertFalse(buffer.enqueue(update(3)))

        val batch = buffer.drainAll()

        assertEquals(listOf(first, second), batch)
        buffer.completeBatch(batch)
    }

    @Test
    fun `restoring a failed batch keeps it ahead of points accepted during the failed attempt`() {
        val buffer = InMemoryVehicleLocationBuffer(maxPendingUpdates = 3)
        val first = update(1)
        val second = update(2)
        val newer = update(3)
        buffer.enqueue(first)
        buffer.enqueue(second)
        val failedBatch = buffer.drainAll()

        assertTrue(buffer.enqueue(newer))
        buffer.restoreFailedBatch(failedBatch)

        val retryBatch = buffer.drainAll()

        assertEquals(listOf(first, second, newer), retryBatch)
        buffer.completeBatch(retryBatch)
    }
}
