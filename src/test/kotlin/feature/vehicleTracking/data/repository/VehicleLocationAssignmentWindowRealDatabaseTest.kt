/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.withRealVehicleTrackingDatabase
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VehicleLocationAssignmentWindowRealDatabaseTest {
    @Test
    fun `assigned history is retained only inside the assignment's start and end timestamps`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val startedAt = Instant.parse("2026-01-01T00:00:10Z")
            val endedAt = Instant.parse("2026-01-01T00:00:20Z")
            val assignment =
                requireNotNull(
                    harness.tx.transactional {
                        harness.assignmentRepository.startAssignment(
                            vehicleId = vehicleId,
                            vendorUserId = 1,
                            startedAt = startedAt,
                        )
                    },
                )
            assertTrue(harness.tx.transactional { harness.assignmentRepository.endAssignment(assignment.id, endedAt) })

            val updates =
                listOf(
                    windowedLocation(vehicleId, startedAt.minusSeconds(1), assignment.id),
                    windowedLocation(vehicleId, startedAt, assignment.id),
                    windowedLocation(vehicleId, endedAt, assignment.id),
                    windowedLocation(vehicleId, endedAt.plusSeconds(1), assignment.id),
                )
            val skipped =
                harness.tx.transactional {
                    harness.locationRepository.insertAllForCurrentReferences(updates)
                }

            assertEquals(2, skipped)
            val persistedTimes =
                harness.tx.transactional {
                    VehicleLocationsTable
                        .selectAll()
                        .orderBy(VehicleLocationsTable.recordedAt to SortOrder.ASC)
                        .map { it[VehicleLocationsTable.recordedAt] }
                }
            assertEquals(listOf(startedAt, endedAt), persistedTimes)
        }
    }

    private fun windowedLocation(
        vehicleId: Int,
        recordedAt: Instant,
        assignmentId: Int,
    ) = VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = 1,
        latitude = 47.4979,
        longitude = 19.0402,
        recordedAt = recordedAt,
        cellId = CellId("test-cell"),
        assignmentId = assignmentId,
    )
}
