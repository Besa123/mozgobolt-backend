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
    }

    @Test
    fun `draining empties the buffer, so a second immediate drain is empty`() {
        val buffer = InMemoryVehicleLocationBuffer()
        buffer.enqueue(update(1))
        buffer.drainAll()

        assertEquals(emptyList(), buffer.drainAll())
    }

    @Test
    fun `only what's enqueued after a drain shows up in the next one`() {
        val buffer = InMemoryVehicleLocationBuffer()
        buffer.enqueue(update(1))
        buffer.drainAll()
        buffer.enqueue(update(2))

        val secondDrain = buffer.drainAll()

        assertEquals(listOf(2), secondDrain.map { it.vehicleId })
    }

    @Test
    fun `concurrent enqueues from many coroutines all land, none lost`() {
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val concurrentEnqueues = 500

            (1..concurrentEnqueues)
                .map { vehicleId -> async(Dispatchers.Default) { buffer.enqueue(update(vehicleId)) } }
                .awaitAll()

            val drained = buffer.drainAll()
            assertEquals(concurrentEnqueues, drained.size)
            assertEquals((1..concurrentEnqueues).toSet(), drained.map { it.vehicleId }.toSet())
            assertTrue(buffer.drainAll().isEmpty(), "every enqueued item must have been drained exactly once")
        }
    }
}
