/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionOutcome
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.model.ActiveVehicleLocationAssignmentSnapshot
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationStateSnapshot
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleLocationStateReconcilerTest {
    @Test
    fun `reconciliation reads the bounded snapshot inside a transaction and hydrates the hub`() =
        runBlocking {
            val asOf = Instant.parse("2026-06-01T12:05:00Z")
            val update =
                VehicleLocationUpdate(
                    vehicleId = 12,
                    vendorUserId = 4,
                    latitude = 47.4979,
                    longitude = 19.0402,
                    recordedAt = asOf.minusSeconds(1),
                    cellId = CellId("test-cell"),
                    sharesLocationWithCustomers = true,
                    assignmentId = 42,
                )
            val repository =
                SnapshotRepository(
                    VehicleLocationStateSnapshot(
                        activeAssignments =
                            listOf(
                                ActiveVehicleLocationAssignmentSnapshot(
                                    vehicleId = update.vehicleId,
                                    assignmentId = requireNotNull(update.assignmentId),
                                    vendorUserId = update.vendorUserId,
                                    startedAt = asOf.minusSeconds(30),
                                    sharesLocationWithCustomers = true,
                                ),
                            ),
                        latestLocations = listOf(update),
                    ),
                )
            val transactionRunner = CountingTransactionalRunner()
            val backgroundScope = ApplicationBackgroundScope()
            val hub = InMemoryVehicleLocationHub(H3CellIndexer())
            try {
                VehicleLocationStateReconciler(
                    repository = repository,
                    tx = transactionRunner,
                    hub = hub,
                    backgroundScope = backgroundScope,
                    clock = Clock.fixed(asOf, ZoneOffset.UTC),
                ).reconcile()

                assertEquals(1, transactionRunner.calls)
                assertEquals(asOf.minusSeconds(300), repository.cutoff)
                assertEquals(asOf, repository.asOf)
                assertEquals(update, hub.lastKnownLocation(update.vehicleId))
            } finally {
                backgroundScope.cancel()
            }
        }

    private class SnapshotRepository(
        private val snapshot: VehicleLocationStateSnapshot,
    ) : VehicleLocationRepository {
        var cutoff: Instant? = null
            private set
        var asOf: Instant? = null
            private set

        override suspend fun findLiveStateSnapshot(
            cutoff: Instant,
            asOf: Instant,
        ): VehicleLocationStateSnapshot {
            this.cutoff = cutoff
            this.asOf = asOf
            return snapshot
        }

        override suspend fun insertAll(updates: List<VehicleLocationUpdate>) = error("not exercised by this test")

        override suspend fun insertAllForCurrentReferences(updates: List<VehicleLocationUpdate>): Int =
            error("not exercised by this test")

        override suspend fun purgeOlderThan(cutoff: Instant): Int = error("not exercised by this test")
    }

    private class CountingTransactionalRunner : TransactionalRunner {
        var calls = 0
            private set

        override suspend fun <T> transactional(block: suspend () -> T): T {
            calls++
            return block()
        }

        override suspend fun <T> transactionalReadOnlySnapshot(block: suspend () -> T): T {
            calls++
            return block()
        }

        override suspend fun <T> transactionalWithOutcome(block: suspend () -> TransactionOutcome<T>): T {
            calls++
            return block().value
        }
    }
}
