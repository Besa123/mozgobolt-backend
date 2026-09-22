package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
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

class VehicleLocationRetentionPurgerTest {
    private class CountingTransactionalRunner : TransactionalRunner {
        var callCount = 0
            private set

        override suspend fun <T> transactional(block: suspend () -> T): T {
            callCount++
            return block()
        }
    }

    private class FakeVehicleLocationRepository : VehicleLocationRepository {
        val purgeCutoffs = mutableListOf<Instant>()

        // Counts every attempt, including ones that go on to throw — mirrors
        // VehicleLocationBatchWriterTest's own reasoning for why this can't just be inferred from
        // purgeCutoffs alone once a throw is involved.
        var attemptCount = 0
            private set
        var throwOnNextPurge: Throwable? = null
        var deletedCountToReturn = 0

        override suspend fun insertAll(updates: List<VehicleLocationUpdate>) = error("not exercised by this test")

        override suspend fun purgeOlderThan(cutoff: Instant): Int {
            attemptCount++
            throwOnNextPurge?.let {
                throwOnNextPurge = null
                throw it
            }
            purgeCutoffs += cutoff
            return deletedCountToReturn
        }
    }

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
    fun `purge delegates to the repository inside one transaction with a cutoff in the past`() =
        runBlocking {
            val repository = FakeVehicleLocationRepository()
            val tx = CountingTransactionalRunner()
            val purger = VehicleLocationRetentionPurger(repository, tx)
            val before = Instant.now()

            purger.purge()

            assertEquals(1, tx.callCount)
            assertEquals(1, repository.purgeCutoffs.size)
            assertTrue(
                repository.purgeCutoffs.single().isBefore(before),
                "the cutoff must be in the past relative to when purge() was called, not the current time",
            )
        }

    @Test
    fun `REGRESSION a failed purge does not permanently kill the periodic loop`() =
        runBlocking {
            // Same shape as VehicleLocationBatchWriterTest's regression test: an exception
            // escaping purge() inside the start() loop must not terminate the whole background
            // job, or every future interval's purge would silently stop firing until restart.
            val repository = FakeVehicleLocationRepository()
            val purger =
                VehicleLocationRetentionPurger(
                    repository = repository,
                    tx = CountingTransactionalRunner(),
                    checkInterval = 20.milliseconds,
                )
            repository.throwOnNextPurge = IOException("simulated transient DB failure")

            try {
                purger.start()
                pollUntil { repository.attemptCount >= 1 }

                pollUntil { repository.purgeCutoffs.isNotEmpty() }
            } finally {
                purger.stop()
            }
        }

    @Test
    fun `stop halts future automatic purges`() =
        runBlocking {
            val repository = FakeVehicleLocationRepository()
            val purger =
                VehicleLocationRetentionPurger(
                    repository = repository,
                    tx = CountingTransactionalRunner(),
                    checkInterval = 20.milliseconds,
                )
            purger.start()
            pollUntil { repository.attemptCount >= 1 }

            purger.stop()
            val countAtStop = repository.attemptCount
            delay(150) // well more than one checkInterval — enough for a still-running loop to (wrongly) fire again

            assertEquals(countAtStop, repository.attemptCount, "stop() must prevent any further automatic purge")
        }
}
