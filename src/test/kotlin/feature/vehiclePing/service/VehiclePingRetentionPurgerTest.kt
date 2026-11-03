/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class VehiclePingRetentionPurgerTest {
    private class CountingTransactionalRunner : TransactionalRunner {
        var callCount = 0
            private set

        override suspend fun <T> transactional(block: suspend () -> T): T {
            callCount++
            return block()
        }
    }

    private class FakeVehiclePingRepository : VehiclePingRepository {
        val cutoffs = mutableListOf<Instant>()
        var attemptCount = 0
            private set
        var throwOnNextPurge: Throwable? = null
        var deletedCount = 0

        override suspend fun lastSentAt(
            vehicleId: Int,
            buyerUserId: Int,
        ): Instant? = error("not exercised by this test")

        override suspend fun recordLatest(
            vehicleId: Int,
            buyerUserId: Int,
            sentAt: Instant,
        ): VehiclePing = error("not exercised by this test")

        override suspend fun purgeOlderThan(cutoff: Instant): Int {
            attemptCount++
            throwOnNextPurge?.let {
                throwOnNextPurge = null
                throw it
            }
            cutoffs += cutoff
            return deletedCount
        }
    }

    private suspend fun pollUntil(
        timeoutMs: Long = 3_000,
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
    fun `purge runs inside one transaction using the configured retention cutoff`() =
        runBlocking {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val repository = FakeVehiclePingRepository()
            val tx = CountingTransactionalRunner()
            val purger =
                VehiclePingRetentionPurger(
                    repository = repository,
                    tx = tx,
                    backgroundScope = ApplicationBackgroundScope(),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                    retentionPeriod = 5.minutes,
                )

            assertEquals(0, purger.purge())

            assertEquals(1, tx.callCount)
            assertEquals(listOf(now.minus(5.minutes.toJavaDuration())), repository.cutoffs)
        }

    @Test
    fun `a transient purge failure does not stop future purge attempts`() =
        runBlocking {
            val repository = FakeVehiclePingRepository()
            val purger =
                VehiclePingRetentionPurger(
                    repository = repository,
                    tx = CountingTransactionalRunner(),
                    backgroundScope = ApplicationBackgroundScope(),
                    checkInterval = 20.milliseconds,
                )
            repository.throwOnNextPurge = IOException("simulated transient failure")

            try {
                purger.start()
                pollUntil { repository.attemptCount >= 2 && repository.cutoffs.isNotEmpty() }
            } finally {
                purger.stop()
            }

            assertTrue(repository.cutoffs.isNotEmpty())
        }

    @Test
    fun `stop prevents subsequent scheduled purge attempts`() =
        runBlocking {
            val repository = FakeVehiclePingRepository()
            val purger =
                VehiclePingRetentionPurger(
                    repository = repository,
                    tx = CountingTransactionalRunner(),
                    backgroundScope = ApplicationBackgroundScope(),
                    checkInterval = 20.milliseconds,
                )
            purger.start()
            pollUntil { repository.attemptCount >= 1 }

            purger.stop()
            val attemptsAtStop = repository.attemptCount
            delay(150)

            assertEquals(attemptsAtStop, repository.attemptCount)
        }
}
