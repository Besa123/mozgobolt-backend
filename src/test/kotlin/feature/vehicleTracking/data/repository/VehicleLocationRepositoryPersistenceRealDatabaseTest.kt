/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.withRealVehicleTrackingDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleLocationRepositoryPersistenceRealDatabaseTest {
    @Test
    fun `retrying the same idempotency key does not create a duplicate history row`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val update =
                locationUpdate(
                    vehicleId = vehicleId,
                    recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )

            harness.tx.transactional { harness.locationRepository.insertAll(listOf(update)) }
            harness.tx.transactional { harness.locationRepository.insertAll(listOf(update)) }

            val persisted =
                harness.tx.transactional {
                    VehicleLocationsTable
                        .selectAll()
                        .where { VehicleLocationsTable.idempotencyKey eq update.idempotencyKey.toString() }
                        .toList()
                }

            assertEquals(1, persisted.size)
            assertEquals(update.latitude, persisted.single()[VehicleLocationsTable.latitude].toDouble())
        }
    }

    @Test
    fun `background persistence skips a stale assignment but keeps unrelated valid history`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val assignmentId =
                requireNotNull(
                    harness.tx.transactional {
                        harness.assignmentRepository.startAssignment(
                            vehicleId = vehicleId,
                            vendorUserId = 1,
                            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                        )
                    },
                ).id
            val staleAssignmentUpdate =
                locationUpdate(
                    vehicleId = vehicleId,
                    assignmentId = assignmentId,
                    recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                )
            val unassignedUpdate =
                locationUpdate(
                    vehicleId = vehicleId,
                    recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                )
            harness.tx.transactional {
                VehicleAssignmentsTable.deleteWhere { VehicleAssignmentsTable.id eq assignmentId }
            }

            val skipped =
                harness.tx.transactional {
                    harness.locationRepository.insertAllForCurrentReferences(
                        listOf(staleAssignmentUpdate, unassignedUpdate),
                    )
                }

            assertEquals(1, skipped)
            assertEquals(1L, harness.countLocations())
            val persistedKey =
                harness.tx.transactional {
                    VehicleLocationsTable
                        .selectAll()
                        .single()[VehicleLocationsTable.idempotencyKey]
                }
            assertEquals(unassignedUpdate.idempotencyKey.toString(), persistedKey)
        }
    }

    @Test
    fun `background persistence accepts matching assignments and skips mismatched vendors`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val assignmentId =
                requireNotNull(
                    harness.tx.transactional {
                        harness.assignmentRepository.startAssignment(
                            vehicleId = vehicleId,
                            vendorUserId = 1,
                            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                        )
                    },
                ).id
            val valid =
                locationUpdate(
                    vehicleId = vehicleId,
                    assignmentId = assignmentId,
                    recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                )
            val mismatchedVendor =
                locationUpdate(
                    vehicleId = vehicleId,
                    vendorUserId = 2,
                    assignmentId = assignmentId,
                    recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                )

            val skipped =
                harness.tx.transactional {
                    harness.locationRepository.insertAllForCurrentReferences(listOf(valid, mismatchedVendor))
                }

            assertEquals(1, skipped)
            assertEquals(1L, harness.countLocations())
            val persisted =
                harness.tx.transactional {
                    VehicleLocationsTable.selectAll().single()
                }
            assertEquals(assignmentId, persisted[VehicleLocationsTable.assignmentId])
            assertEquals(valid.idempotencyKey.toString(), persisted[VehicleLocationsTable.idempotencyKey])
        }
    }

    @Test
    fun `background persistence skips malformed points and keeps valid history`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val valid =
                locationUpdate(
                    vehicleId = vehicleId,
                    recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            val invalidPoints =
                listOf(
                    locationUpdate(
                        vehicleId = vehicleId,
                        latitude = Double.NaN,
                        recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        longitude = Double.POSITIVE_INFINITY,
                        recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        latitude = GeoBounds.MAX_LATITUDE + 0.000001,
                        recordedAt = Instant.parse("2026-01-01T00:00:03Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        recordedAt = Instant.parse("2026-01-01T00:00:04Z"),
                    ).copy(cellId = CellId("x".repeat(H3CellIndexer.CELL_ADDRESS_MAX_LENGTH + 1))),
                    locationUpdate(
                        vehicleId = vehicleId,
                        recordedAt = Instant.MAX,
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        recordedAt = Instant.parse("+1000000-01-01T00:00:00Z"),
                    ),
                )

            val skipped =
                harness.tx.transactional {
                    harness.locationRepository.insertAllForCurrentReferences(listOf(valid) + invalidPoints)
                }

            assertEquals(invalidPoints.size, skipped)
            assertEquals(1L, harness.countLocations())
            val persistedKey =
                harness.tx.transactional {
                    VehicleLocationsTable
                        .selectAll()
                        .single()[VehicleLocationsTable.idempotencyKey]
                }
            assertEquals(valid.idempotencyKey.toString(), persistedKey)
        }
    }

    private fun locationUpdate(
        vehicleId: Int,
        vendorUserId: Int = 1,
        latitude: Double = 47.4979,
        longitude: Double = 19.0402,
        recordedAt: Instant,
        assignmentId: Int? = null,
    ) = VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt,
        cellId = CellId("test-cell"),
        assignmentId = assignmentId,
    )
}
