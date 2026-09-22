package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class VehicleLocationBatchWriterTest {
    private class CountingTransactionalRunner : TransactionalRunner {
        var callCount = 0
            private set

        override suspend fun <T> transactional(block: suspend () -> T): T {
            callCount++
            return block()
        }
    }

    private class FakeVehicleLocationRepository : VehicleLocationRepository {
        val insertAllCalls = mutableListOf<List<VehicleLocationUpdate>>()

        // Counts every attempt, including ones that go on to throw — tests need to observe "a
        // flush was attempted" independent of whether it succeeded, without resorting to peeking
        // the buffer (buffer.drainAll() is destructive; polling on it races the writer itself).
        var attemptCount = 0
            private set
        var throwOnNextInsert: Throwable? = null

        override suspend fun insertAll(updates: List<VehicleLocationUpdate>) {
            attemptCount++
            throwOnNextInsert?.let {
                throwOnNextInsert = null
                throw it
            }
            insertAllCalls += updates
        }

        override suspend fun purgeOlderThan(cutoff: Instant): Int = error("not exercised by this test")
    }

    private fun update(vehicleId: Int) =
        VehicleLocationUpdate(
            vehicleId = vehicleId,
            vendorUserId = vehicleId,
            latitude = 0.0,
            longitude = 0.0,
            recordedAt = Instant.now(),
            cellId = CellId("irrelevant"),
        )

    private suspend fun pollUntil(
        timeoutMs: Long = 3000,
        intervalMs: Long = 15,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(intervalMs)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `flushing an empty buffer touches neither the repository nor a transaction`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val tx = CountingTransactionalRunner()
            val writer = VehicleLocationBatchWriter(buffer, repository, tx)

            writer.flush()

            assertTrue(repository.insertAllCalls.isEmpty())
            assertEquals(0, tx.callCount)
        }

    @Test
    fun `flushing drains the buffer and inserts everything in one transactional batch`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val tx = CountingTransactionalRunner()
            val writer = VehicleLocationBatchWriter(buffer, repository, tx)
            buffer.enqueue(update(1))
            buffer.enqueue(update(2))
            buffer.enqueue(update(3))

            writer.flush()

            assertEquals(1, repository.insertAllCalls.size, "all three should land in a single batch insert")
            assertEquals(listOf(1, 2, 3), repository.insertAllCalls.single().map { it.vehicleId })
            assertEquals(1, tx.callCount)
            assertTrue(buffer.drainAll().isEmpty(), "the buffer must be empty after a successful flush")
        }

    @Test
    fun `a second flush with nothing new enqueued does not insert an empty batch`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val writer = VehicleLocationBatchWriter(buffer, repository, CountingTransactionalRunner())
            buffer.enqueue(update(1))
            writer.flush()

            writer.flush()

            assertEquals(1, repository.insertAllCalls.size)
        }

    @Test
    fun `REGRESSION a failed flush does not permanently kill the periodic writer`() =
        runBlocking {
            // Before the fix: an exception escaping flush() inside the start() loop terminated
            // the whole background job — the very next interval's data would never be written,
            // silently, until the process restarted. This test fails (times out) against that
            // old behavior and passes now that a per-iteration failure is caught and logged.
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val writer =
                VehicleLocationBatchWriter(
                    buffer = buffer,
                    repository = repository,
                    tx = CountingTransactionalRunner(),
                    flushInterval = 20.milliseconds,
                )
            repository.throwOnNextInsert = IOException("simulated transient DB failure")
            buffer.enqueue(update(1))

            try {
                writer.start()
                // Wait for the first (doomed-to-fail) flush attempt — NOT by peeking the buffer,
                // which is destructive (drainAll() actually removes items) and would race the
                // writer's own drain, corrupting the very thing being observed.
                pollUntil { repository.attemptCount >= 1 }

                buffer.enqueue(update(2))
                pollUntil { repository.insertAllCalls.isNotEmpty() }

                assertEquals(listOf(2), repository.insertAllCalls.single().map { it.vehicleId })
            } finally {
                writer.stop()
            }
        }

    @Test
    fun `stop halts future automatic flushes`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val writer =
                VehicleLocationBatchWriter(
                    buffer = buffer,
                    repository = repository,
                    tx = CountingTransactionalRunner(),
                    flushInterval = 20.milliseconds,
                )
            buffer.enqueue(update(1))
            writer.start()
            pollUntil { repository.insertAllCalls.isNotEmpty() }

            writer.stop()
            buffer.enqueue(update(2))
            delay(150) // well more than one flushInterval — enough for a still-running loop to (wrongly) fire again

            assertEquals(1, repository.insertAllCalls.size, "stop() must prevent any further automatic flush")
            assertEquals(listOf(update(2).vehicleId), buffer.drainAll().map { it.vehicleId })
        }
}
