/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.sql.SQLException
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
        val attemptedBatches = mutableListOf<List<VehicleLocationUpdate>>()

        // Counts every attempt, including ones that go on to throw — tests need to observe "a
        // flush was attempted" independent of whether it succeeded, without resorting to peeking
        // the buffer (buffer.drainAll() is destructive; polling on it races the writer itself).
        var attemptCount = 0
            private set
        var throwOnNextInsert: Throwable? = null
        var rejectVehicleIds: Set<Int> = emptySet()
        var failFilteredSingleton = false
        var beforeNextInsert: (suspend () -> Unit)? = null

        override suspend fun insertAll(updates: List<VehicleLocationUpdate>) {
            attemptCount++
            attemptedBatches += updates.toList()
            throwOnNextInsert?.let {
                throwOnNextInsert = null
                throw it
            }
            if (updates.any { it.vehicleId in rejectVehicleIds }) {
                throw SQLException("simulated permanent row constraint failure", "23514")
            }
            val beforeInsert = beforeNextInsert
            beforeNextInsert = null
            beforeInsert?.invoke()
            insertAllCalls += updates.toList()
        }

        override suspend fun insertAllForCurrentReferences(updates: List<VehicleLocationUpdate>): Int {
            val isRejectedSingleton =
                updates.singleOrNull()?.vehicleId?.let { it in rejectVehicleIds } == true
            if (isRejectedSingleton && !failFilteredSingleton) {
                attemptCount++
                attemptedBatches += updates.toList()
                return 1
            }
            insertAll(updates)
            return 0
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
            val writer = VehicleLocationBatchWriter(buffer, repository, tx, ApplicationBackgroundScope())

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
            val writer = VehicleLocationBatchWriter(buffer, repository, tx, ApplicationBackgroundScope())
            val first = update(1)
            buffer.enqueue(first)
            buffer.enqueue(update(2))
            buffer.enqueue(update(3))

            writer.flush()

            assertEquals(1, repository.insertAllCalls.size, "all three should land in a single batch insert")
            assertEquals(listOf(1, 2, 3), repository.insertAllCalls.single().map { it.vehicleId })
            assertEquals(1, tx.callCount)
            assertTrue(buffer.drainAll().isEmpty(), "the buffer must be empty after a successful flush")
        }

    @Test
    fun `a permanent row failure drops only the invalid point and persists valid siblings`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val tx = CountingTransactionalRunner()
            val writer = VehicleLocationBatchWriter(buffer, repository, tx, ApplicationBackgroundScope())
            val updates = listOf(update(1), update(2), update(3))
            repository.rejectVehicleIds = setOf(2)
            updates.forEach(buffer::enqueue)

            writer.flush()

            assertEquals(
                listOf(1, 3),
                repository.insertAllCalls.flatMap { it }.map { it.vehicleId },
                "a permanent constraint failure must not discard valid points from the same batch",
            )
            assertEquals(
                listOf(listOf(1, 2, 3), listOf(1), listOf(2, 3), listOf(2), listOf(3)),
                repository.attemptedBatches.map { batch -> batch.map { it.vehicleId } },
            )
            assertEquals(5, tx.callCount)
            assertTrue(buffer.drainAll().isEmpty(), "persisted and isolated rows must release their capacity")
        }

    @Test
    fun `an unrecognized permanent singleton failure is surfaced and the whole batch remains queued`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository =
                FakeVehicleLocationRepository().apply {
                    rejectVehicleIds = setOf(2)
                    failFilteredSingleton = true
                }
            val writer =
                VehicleLocationBatchWriter(
                    buffer = buffer,
                    repository = repository,
                    tx = CountingTransactionalRunner(),
                    backgroundScope = ApplicationBackgroundScope(),
                )
            val batch = listOf(update(1), update(2))
            batch.forEach(buffer::enqueue)

            val failure =
                kotlin.test.assertFailsWith<SQLException> {
                    writer.flush()
                }

            assertEquals("23514", failure.sqlState)
            val restored = buffer.drainAll()
            assertEquals(batch.map { it.vehicleId }, restored.map { it.vehicleId })
            buffer.completeBatch(restored)
        }

    @Test
    fun `cancelling a persistence attempt restores the batch for the next flush`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val writer =
                VehicleLocationBatchWriter(
                    buffer,
                    repository,
                    CountingTransactionalRunner(),
                    ApplicationBackgroundScope(),
                )
            val insertStarted = CompletableDeferred<Unit>()
            val finishInsert = CompletableDeferred<Unit>()
            val update = update(1)
            repository.beforeNextInsert = {
                insertStarted.complete(Unit)
                finishInsert.await()
            }
            buffer.enqueue(update)

            val cancelledFlush = async { writer.flush() }
            insertStarted.await()
            cancelledFlush.cancelAndJoin()

            writer.flush()

            assertEquals(
                listOf(update.idempotencyKey),
                repository.insertAllCalls
                    .single()
                    .map { it.idempotencyKey },
            )
            assertTrue(buffer.drainAll().isEmpty())
        }

    @Test
    fun `a second flush with nothing new enqueued does not insert an empty batch`() =
        runBlocking {
            val buffer = InMemoryVehicleLocationBuffer()
            val repository = FakeVehicleLocationRepository()
            val writer =
                VehicleLocationBatchWriter(
                    buffer,
                    repository,
                    CountingTransactionalRunner(),
                    ApplicationBackgroundScope(),
                )
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
                    backgroundScope = ApplicationBackgroundScope(),
                    flushInterval = 20.milliseconds,
                )
            repository.throwOnNextInsert = IOException("simulated transient DB failure")
            val first = update(1)
            buffer.enqueue(first)

            try {
                writer.start()
                // Wait for the first (doomed-to-fail) flush attempt — NOT by peeking the buffer,
                // which is destructive (drainAll() actually removes items) and would race the
                // writer's own drain, corrupting the very thing being observed.
                pollUntil { repository.attemptCount >= 1 }

                val second = update(2)
                buffer.enqueue(second)
                pollUntil { repository.insertAllCalls.isNotEmpty() }

                assertEquals(
                    listOf(1, 2),
                    repository.insertAllCalls
                        .single()
                        .map { it.vehicleId },
                )
                assertEquals(
                    listOf(first.idempotencyKey, second.idempotencyKey),
                    repository.insertAllCalls
                        .single()
                        .map { it.idempotencyKey },
                )
                assertEquals(
                    first.idempotencyKey,
                    repository.attemptedBatches
                        .first()
                        .single()
                        .idempotencyKey,
                    "the retry must keep the original point's idempotency key",
                )
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
                    backgroundScope = ApplicationBackgroundScope(),
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
