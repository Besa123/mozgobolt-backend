/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.user.data.database.UserEntity
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.withRealVehicleTrackingDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class VehicleLocationStateSnapshotRealDatabaseTest {
    @Test
    fun `live-state snapshot chooses the latest recent point from only active consented assignments`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val asOf = Instant.parse("2026-06-01T12:05:00Z")
            val cutoff = asOf.minusSeconds(5 * 60)
            val startedAt = asOf.minusSeconds(60 * 60)
            val publicVehicleId = harness.seedVehicle()
            val privateVehicleId = harness.seedVehicle()
            val endedVehicleId = harness.seedVehicle()
            val thirdVendorId =
                harness.tx.transactional {
                    UserEntity
                        .new {
                            email = "snapshot-vendor@example.com"
                            name = "Snapshot Vendor"
                            passwordHash = "unused"
                            role = UserRole.VENDOR
                        }.id.value
                }
            val publicAssignment =
                requireNotNull(
                    harness.tx.transactional {
                        harness.assignmentRepository.startAssignment(
                            vehicleId = publicVehicleId,
                            vendorUserId = 1,
                            startedAt = startedAt,
                            locationSharingConsentedAt = startedAt,
                        )
                    },
                )
            val privateAssignment =
                requireNotNull(
                    harness.tx.transactional {
                        harness.assignmentRepository.startAssignment(
                            vehicleId = privateVehicleId,
                            vendorUserId = 2,
                            startedAt = startedAt,
                        )
                    },
                )
            val endedAssignment =
                requireNotNull(
                    harness.tx.transactional {
                        harness.assignmentRepository.startAssignment(
                            vehicleId = endedVehicleId,
                            vendorUserId = thirdVendorId,
                            startedAt = startedAt,
                            locationSharingConsentedAt = startedAt,
                        )
                    },
                )
            assertTrue(
                harness.tx.transactional {
                    harness.assignmentRepository.endAssignment(endedAssignment.id, asOf.minusSeconds(1))
                },
            )

            val latestPublicPoint =
                locationUpdate(
                    publicVehicleId,
                    vendorUserId = 1,
                    assignmentId = publicAssignment.id,
                    recordedAt = asOf.minusSeconds(2),
                )
            harness.tx.transactional {
                harness.locationRepository.insertAllForCurrentReferences(
                    listOf(
                        locationUpdate(
                            publicVehicleId,
                            vendorUserId = 1,
                            assignmentId = publicAssignment.id,
                            recordedAt = cutoff.minusSeconds(1),
                        ),
                        latestPublicPoint,
                        locationUpdate(
                            publicVehicleId,
                            vendorUserId = 1,
                            assignmentId = publicAssignment.id,
                            recordedAt = asOf.plusSeconds(1),
                        ),
                        locationUpdate(
                            privateVehicleId,
                            vendorUserId = 2,
                            assignmentId = privateAssignment.id,
                            recordedAt = asOf.minusSeconds(1),
                        ),
                        locationUpdate(
                            endedVehicleId,
                            vendorUserId = thirdVendorId,
                            assignmentId = endedAssignment.id,
                            recordedAt = asOf.minusSeconds(1),
                        ),
                    ),
                )
            }

            val snapshot =
                harness.tx.transactionalReadOnlySnapshot {
                    harness.locationRepository.findLiveStateSnapshot(cutoff, asOf)
                }

            assertEquals(
                setOf(publicVehicleId, privateVehicleId),
                snapshot.activeAssignments.map { it.vehicleId }.toSet(),
            )
            assertTrue(
                snapshot.activeAssignments.single { it.vehicleId == publicVehicleId }.sharesLocationWithCustomers,
            )
            assertFalse(
                snapshot.activeAssignments.single { it.vehicleId == privateVehicleId }.sharesLocationWithCustomers,
            )
            assertEquals(listOf(latestPublicPoint), snapshot.latestLocations)
        }
    }

    @Test
    fun `live-state snapshot keeps assignment and location reads in one PostgreSQL snapshot`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val asOf = Instant.parse("2026-06-01T12:05:00Z")
            val cutoff = asOf.minusSeconds(5 * 60)
            val startedAt = asOf.minusSeconds(60 * 60)
            val vehicleId = harness.seedVehicle()
            val assignment =
                requireNotNull(
                    harness.tx.transactional {
                        harness.assignmentRepository.startAssignment(
                            vehicleId = vehicleId,
                            vendorUserId = 1,
                            startedAt = startedAt,
                            locationSharingConsentedAt = startedAt,
                        )
                    },
                )
            val latestPoint =
                locationUpdate(
                    vehicleId = vehicleId,
                    vendorUserId = 1,
                    assignmentId = assignment.id,
                    recordedAt = asOf.minusSeconds(3),
                )
            harness.tx.transactional {
                harness.locationRepository.insertAllForCurrentReferences(listOf(latestPoint))
            }

            coroutineScope {
                val firstReadCompleted = CompletableDeferred<Unit>()
                val resumeSnapshot = CompletableDeferred<Unit>()
                val snapshotRead =
                    async {
                        harness.tx.transactionalReadOnlySnapshot {
                            VehicleAssignmentsTable
                                .select(VehicleAssignmentsTable.id)
                                .where {
                                    (VehicleAssignmentsTable.vehicleId eq vehicleId) and
                                        VehicleAssignmentsTable.endedAt.isNull()
                                }.single()
                            firstReadCompleted.complete(Unit)
                            resumeSnapshot.await()
                            harness.locationRepository.findLiveStateSnapshot(cutoff, asOf)
                        }
                    }

                try {
                    withTimeout(5.seconds) { firstReadCompleted.await() }
                    assertTrue(
                        harness.tx.transactional {
                            harness.assignmentRepository.endAssignment(assignment.id, asOf.minusSeconds(2))
                        },
                    )
                    resumeSnapshot.complete(Unit)

                    val consistentSnapshot = withTimeout(5.seconds) { snapshotRead.await() }
                    assertEquals(
                        setOf(vehicleId),
                        consistentSnapshot.activeAssignments.map { it.vehicleId }.toSet(),
                    )
                    assertEquals(listOf(latestPoint), consistentSnapshot.latestLocations)

                    val currentSnapshot =
                        harness.tx.transactionalReadOnlySnapshot {
                            harness.locationRepository.findLiveStateSnapshot(cutoff, asOf)
                        }
                    assertTrue(currentSnapshot.activeAssignments.isEmpty())
                    assertTrue(currentSnapshot.latestLocations.isEmpty())
                } finally {
                    resumeSnapshot.complete(Unit)
                    snapshotRead.cancelAndJoin()
                }
            }
        }
    }

    private fun locationUpdate(
        vehicleId: Int,
        vendorUserId: Int,
        assignmentId: Int,
        recordedAt: Instant,
    ) = VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        latitude = 47.4979,
        longitude = 19.0402,
        recordedAt = recordedAt,
        cellId = CellId("test-cell"),
        sharesLocationWithCustomers = true,
        assignmentId = assignmentId,
    )
}
