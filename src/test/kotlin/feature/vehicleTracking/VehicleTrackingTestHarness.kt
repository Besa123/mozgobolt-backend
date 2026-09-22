package com.mozgobolt.feature.vehicleTracking

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.data.repository.VehicleLocationRepositoryI
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

data class VehicleTrackingTestHarness(
    val locationRepository: VehicleLocationRepositoryI,
    val assignmentRepository: VehicleAssignmentRepositoryI,
    val tx: TransactionalRunner,
) {
    /** A vehicle in a fresh company — the shared starting point every test in this harness needs. */
    suspend fun seedVehicle(): Int =
        tx.transactional {
            val company =
                CompanyRepositoryI().create(
                    name = "Retention Test Co",
                    inviteCode = "seed-${Instant.now().toEpochMilli()}",
                )
            VehicleRepositoryI()
                .create(companyId = company.id, label = "Retention Truck", licensePlate = "RETAIN-1")!!
                .id
        }

    /** Starts and immediately ends an assignment for [vehicleId], backdated so its `endedAt` is
     * exactly [endedAt] — lets a test control the retention cutoff precisely, rather than relying
     * on real wall-clock elapsed time. Uses vendor user id 1, one of [withRealDatabase]'s two
     * seeded users. */
    suspend fun seedEndedAssignment(
        vehicleId: Int,
        endedAt: Instant,
        vendorUserId: Int = 1,
    ): Int =
        tx.transactional {
            val assignment = assignmentRepository.startAssignment(vehicleId, vendorUserId, endedAt)!!
            assignmentRepository.endAssignment(assignment.id, endedAt)
            assignment.id
        }

    /** A still-active assignment (never ended) — must never be affected by the retention purge,
     * regardless of how old its points are. */
    suspend fun seedActiveAssignment(
        vehicleId: Int,
        startedAt: Instant,
        vendorUserId: Int = 1,
    ): Int =
        tx.transactional {
            assignmentRepository.startAssignment(vehicleId, vendorUserId, startedAt)!!.id
        }

    suspend fun seedLocation(
        vehicleId: Int,
        assignmentId: Int?,
        recordedAt: Instant,
    ) = tx.transactional {
        locationRepository.insertAll(
            listOf(
                VehicleLocationUpdate(
                    vehicleId = vehicleId,
                    vendorUserId = 1,
                    latitude = 47.4979,
                    longitude = 19.0402,
                    recordedAt = recordedAt,
                    cellId = CellId("irrelevant"),
                    assignmentId = assignmentId,
                ),
            ),
        )
    }

    suspend fun countLocationsForAssignment(assignmentId: Int?): Int =
        tx.transactional {
            VehicleLocationsTable
                .selectAll()
                .count { it[VehicleLocationsTable.assignmentId] == assignmentId }
        }
}

fun withRealVehicleTrackingDatabase(block: suspend (VehicleTrackingTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        block(
            VehicleTrackingTestHarness(
                locationRepository = VehicleLocationRepositoryI(),
                assignmentRepository = VehicleAssignmentRepositoryI(),
                tx = tx,
            ),
        )
    }
}
