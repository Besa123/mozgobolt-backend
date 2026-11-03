/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.withRealVehicleTrackingDatabase
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VehicleLocationBatchWriterRealDatabaseTest {
    private class CheckFailureOnMultiRowRepository(
        private val delegate: VehicleLocationRepository,
    ) : VehicleLocationRepository by delegate {
        var multiRowAttempts = 0
            private set

        override suspend fun insertAllForCurrentReferences(updates: List<VehicleLocationUpdate>): Int {
            if (updates.size > 1) {
                multiRowAttempts++
                // Force the real PostgreSQL CHECK constraint to abort this chunk. The writer must
                // let that transaction roll back before it retries smaller chunks.
                delegate.insertAll(updates)
            }
            return delegate.insertAllForCurrentReferences(updates)
        }
    }

    @Test
    fun `a real PostgreSQL row failure rolls back its chunk then preserves valid siblings`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val validBefore =
                update(
                    vehicleId = vehicleId,
                    latitude = 47.49,
                    recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            val invalid =
                update(
                    vehicleId = vehicleId,
                    latitude = 91.0,
                    recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                )
            val validAfter =
                update(
                    vehicleId = vehicleId,
                    latitude = 47.50,
                    recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                )
            val buffer = InMemoryVehicleLocationBuffer(maxPendingUpdates = 3)
            listOf(validBefore, invalid, validAfter).forEach { update ->
                assertTrue(buffer.enqueue(update))
            }
            val repository = CheckFailureOnMultiRowRepository(harness.locationRepository)
            val writer =
                VehicleLocationBatchWriter(
                    buffer = buffer,
                    repository = repository,
                    tx = harness.tx,
                    backgroundScope = ApplicationBackgroundScope(),
                )

            writer.flush()

            assertTrue(
                repository.multiRowAttempts >= 2,
                "the failed batch and its mixed sub-batch should both be retried",
            )
            assertTrue(buffer.drainAll().isEmpty())
            val persistedKeys =
                harness.tx.transactional {
                    VehicleLocationsTable
                        .selectAll()
                        .map { it[VehicleLocationsTable.idempotencyKey] }
                        .toSet()
                }
            assertEquals(
                setOf(validBefore.idempotencyKey.toString(), validAfter.idempotencyKey.toString()),
                persistedKeys,
            )
        }
    }

    private fun update(
        vehicleId: Int,
        latitude: Double,
        recordedAt: Instant,
    ) = VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = 1,
        latitude = latitude,
        longitude = 19.04,
        recordedAt = recordedAt,
        cellId = CellId("test-cell"),
    )
}
