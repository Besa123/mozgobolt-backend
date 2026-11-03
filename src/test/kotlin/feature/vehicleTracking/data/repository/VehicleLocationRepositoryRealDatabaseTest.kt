/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.withRealVehicleTrackingDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleLocationRepositoryRealDatabaseTest {
    @Test
    fun `both legal coordinate extremes persist exactly in a batch`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val updates =
                listOf(
                    locationUpdate(
                        vehicleId = vehicleId,
                        latitude = GeoBounds.MIN_LATITUDE,
                        longitude = GeoBounds.MIN_LONGITUDE,
                        recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        latitude = GeoBounds.MAX_LATITUDE,
                        longitude = GeoBounds.MAX_LONGITUDE,
                        recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                    ),
                )

            harness.tx.transactional { harness.locationRepository.insertAll(updates) }

            val persistedCoordinates =
                harness.tx.transactional {
                    VehicleLocationsTable
                        .selectAll()
                        .orderBy(VehicleLocationsTable.id to SortOrder.ASC)
                        .map { row ->
                            row[VehicleLocationsTable.latitude].toDouble() to
                                row[VehicleLocationsTable.longitude].toDouble()
                        }
                }
            assertEquals(
                listOf(
                    GeoBounds.MIN_LATITUDE to GeoBounds.MIN_LONGITUDE,
                    GeoBounds.MAX_LATITUDE to GeoBounds.MAX_LONGITUDE,
                ),
                persistedCoordinates,
            )
        }
    }

    @Test
    fun `out-of-range coordinates reject the whole batch`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val valid =
                locationUpdate(
                    vehicleId = vehicleId,
                    recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            val invalidCoordinates =
                listOf(
                    locationUpdate(
                        vehicleId = vehicleId,
                        latitude = 90.000001,
                        recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        latitude = -90.000001,
                        recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        longitude = 180.000001,
                        recordedAt = Instant.parse("2026-01-01T00:00:03Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        longitude = -180.000001,
                        recordedAt = Instant.parse("2026-01-01T00:00:04Z"),
                    ),
                )

            invalidCoordinates.forEach { invalid ->
                val failure =
                    assertFails {
                        harness.tx.transactional {
                            harness.locationRepository.insertAll(listOf(valid, invalid))
                        }
                    }

                assertSqlState(failure, "23514")
                assertEquals(0L, harness.countLocations(), "a failed batch must not leave its valid row behind")
            }
        }
    }

    @Test
    fun `a missing vehicle vendor or assignment foreign key rolls back every row in the batch`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val valid = locationUpdate(vehicleId, recordedAt = Instant.parse("2026-01-01T00:00:00Z"))
            val orphans =
                listOf(
                    locationUpdate(
                        vehicleId = Int.MAX_VALUE,
                        recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        assignmentId = Int.MAX_VALUE,
                        recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        vendorUserId = Int.MAX_VALUE,
                        recordedAt = Instant.parse("2026-01-01T00:00:03Z"),
                    ),
                )

            orphans.forEach { orphan ->
                val failure =
                    assertFails {
                        harness.tx.transactional {
                            harness.locationRepository.insertAll(listOf(valid, orphan))
                        }
                    }

                assertSqlState(failure, "23503")
                assertEquals(0L, harness.countLocations())
            }
        }
    }

    @Test
    fun `assignment identity must match both the telemetry vehicle and vendor`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleRepository = VehicleRepositoryI()
            val vehicleId = harness.seedVehicle()
            val companyId = requireNotNull(harness.tx.transactional { vehicleRepository.findById(vehicleId) }).companyId
            val otherVehicleId =
                requireNotNull(
                    harness.tx.transactional {
                        vehicleRepository.create(companyId, "Other truck", "OTHER-LOCATION-PLATE")
                    },
                ).id
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
            val inconsistentUpdates =
                listOf(
                    locationUpdate(
                        vehicleId = otherVehicleId,
                        vendorUserId = 1,
                        assignmentId = assignmentId,
                        recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                    ),
                    locationUpdate(
                        vehicleId = vehicleId,
                        vendorUserId = 2,
                        assignmentId = assignmentId,
                        recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                    ),
                )

            inconsistentUpdates.forEach { inconsistent ->
                val failure =
                    assertFails {
                        harness.tx.transactional {
                            harness.locationRepository.insertAll(
                                listOf(
                                    locationUpdate(vehicleId, recordedAt = Instant.parse("2026-01-01T00:00:03Z")),
                                    inconsistent,
                                ),
                            )
                        }
                    }

                assertSqlState(failure, "23503")
                assertEquals(0L, harness.countLocations())
            }
        }
    }

    @Test
    fun `an assigned location cannot omit the vendor required by its assignment`() {
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

            val failure =
                assertFails {
                    harness.tx.transactional {
                        VehicleLocationsTable.insert {
                            it[VehicleLocationsTable.vehicleId] = vehicleId
                            it[VehicleLocationsTable.vendorUserId] = null
                            it[VehicleLocationsTable.assignmentId] = assignmentId
                            it[VehicleLocationsTable.latitude] = BigDecimal("47.497900")
                            it[VehicleLocationsTable.longitude] = BigDecimal("19.040200")
                            it[VehicleLocationsTable.recordedAt] = Instant.parse("2026-01-01T00:00:01Z")
                            it[VehicleLocationsTable.cellId] = "test-cell"
                            it[VehicleLocationsTable.idempotencyKey] = UUID.randomUUID().toString()
                        }
                    }
                }

            assertSqlState(failure, "23514")
            assertEquals(0L, harness.countLocations())
        }
    }

    @Test
    fun `deleting an assignment cascades only locations linked to that assignment`() {
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
            harness.tx.transactional {
                harness.locationRepository.insertAll(
                    listOf(
                        locationUpdate(
                            vehicleId = vehicleId,
                            assignmentId = assignmentId,
                            recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                        ),
                        locationUpdate(
                            vehicleId = vehicleId,
                            recordedAt = Instant.parse("2026-01-01T00:00:02Z"),
                        ),
                    ),
                )
            }

            assertEquals(
                1,
                harness.tx.transactional {
                    VehicleAssignmentsTable.deleteWhere { VehicleAssignmentsTable.id eq assignmentId }
                },
            )

            assertEquals(0, harness.countLocationsForAssignment(assignmentId))
            assertEquals(1, harness.countLocationsForAssignment(null))
        }
    }

    @Test
    fun `assignment deletion racing a telemetry insert cannot leave an orphan`() {
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
            val deletionStarted = CompletableDeferred<Unit>()
            val allowDeletionToCommit = CompletableDeferred<Unit>()
            val insertionStarted = CompletableDeferred<Unit>()

            coroutineScope {
                val deletion =
                    async(Dispatchers.IO) {
                        harness.tx.transactional {
                            val deleted =
                                VehicleAssignmentsTable.deleteWhere {
                                    VehicleAssignmentsTable.id eq assignmentId
                                }
                            deletionStarted.complete(Unit)
                            allowDeletionToCommit.await()
                            deleted
                        }
                    }

                try {
                    withTimeout(5_000) { deletionStarted.await() }
                    val insertion =
                        async(Dispatchers.IO) {
                            insertionStarted.complete(Unit)
                            runSuspendCatching {
                                harness.tx.transactional {
                                    harness.locationRepository.insertAll(
                                        listOf(
                                            locationUpdate(
                                                vehicleId = vehicleId,
                                                assignmentId = assignmentId,
                                                recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    withTimeout(5_000) { insertionStarted.await() }

                    assertNull(
                        withTimeoutOrNull(250) { insertion.await() },
                        "the insert must wait for the in-flight assignment deletion",
                    )

                    allowDeletionToCommit.complete(Unit)
                    assertEquals(1, deletion.await())
                    val insertionResult = withTimeout(5_000) { insertion.await() }
                    assertTrue(insertionResult.isFailure, "the deleted assignment must reject the late history row")
                    assertSqlState(checkNotNull(insertionResult.exceptionOrNull()), "23503")
                    assertEquals(0L, harness.countLocations())
                } finally {
                    allowDeletionToCommit.complete(Unit)
                }
            }
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

    private fun assertSqlState(
        failure: Throwable,
        sqlState: String,
    ) {
        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>()
                .any { it.sqlState == sqlState },
            "expected PostgreSQL SQLSTATE $sqlState",
        )
    }
}
