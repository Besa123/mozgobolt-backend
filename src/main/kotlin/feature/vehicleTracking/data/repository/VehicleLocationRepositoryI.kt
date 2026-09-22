package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentEntity
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.math.BigDecimal
import java.time.Instant

class VehicleLocationRepositoryI : VehicleLocationRepository {
    override suspend fun insertAll(updates: List<VehicleLocationUpdate>) {
        if (updates.isEmpty()) return

        VehicleLocationsTable.batchInsert(updates) { update ->
            this[VehicleLocationsTable.vehicleId] = update.vehicleId
            this[VehicleLocationsTable.vendorUserId] = update.vendorUserId
            this[VehicleLocationsTable.assignmentId] = update.assignmentId
            this[VehicleLocationsTable.latitude] = BigDecimal.valueOf(update.latitude)
            this[VehicleLocationsTable.longitude] = BigDecimal.valueOf(update.longitude)
            this[VehicleLocationsTable.recordedAt] = update.recordedAt
            this[VehicleLocationsTable.cellId] = update.cellId.value
        }
    }

    override suspend fun purgeOlderThan(cutoff: Instant): Int {
        val staleAssignmentIds =
            VehicleAssignmentEntity
                .find { VehicleAssignmentsTable.endedAt.isNotNull() and (VehicleAssignmentsTable.endedAt less cutoff) }
                .map { it.id.value }

        // A future "retain this session" opt-out (e.g. a flag on vehicle_assignments) would just
        // need one more AND clause here — not built yet, per product decision.
        return VehicleLocationsTable.deleteWhere {
            (assignmentId inList staleAssignmentIds) or
                (assignmentId.isNull() and (recordedAt less cutoff))
        }
    }
}
