package com.mozgobolt.feature.vehicleAssignment.data.repository

import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentEntity
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleAssignment.data.mapper.toVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class VehicleAssignmentRepositoryI : VehicleAssignmentRepository {
    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
        VehicleAssignmentEntity
            .find {
                (VehicleAssignmentsTable.vendorUserId eq vendorUserId) and
                    VehicleAssignmentsTable.endedAt.isNull()
            }.firstOrNull()
            ?.toVehicleAssignment()

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
        VehicleAssignmentEntity
            .find {
                (VehicleAssignmentsTable.vehicleId eq vehicleId) and
                    VehicleAssignmentsTable.endedAt.isNull()
            }.firstOrNull()
            ?.toVehicleAssignment()

    // insertIgnore (Postgres `ON CONFLICT DO NOTHING`, no target needed since both partial
    // unique indexes should be treated the same way) rather than catching a constraint-violation
    // exception: a unique violation aborts the *entire* Postgres transaction, not just this
    // statement, so anything caught here would still need a SAVEPOINT to keep the transaction
    // usable afterward. insertIgnore never raises the error in the first place — same proven
    // pattern this codebase already used for exactly this kind of race (see git history).
    override suspend fun startAssignment(
        vehicleId: Int,
        vendorUserId: Int,
        startedAt: Instant,
    ): VehicleAssignment? {
        val insertedCount =
            VehicleAssignmentsTable
                .insertIgnore {
                    it[VehicleAssignmentsTable.vehicleId] = vehicleId
                    it[VehicleAssignmentsTable.vendorUserId] = vendorUserId
                    it[VehicleAssignmentsTable.startedAt] = startedAt
                }.insertedCount

        return insertedCount.takeIf { it > 0 }?.let {
            VehicleAssignmentEntity
                .find {
                    (VehicleAssignmentsTable.vehicleId eq vehicleId) and
                        (VehicleAssignmentsTable.vendorUserId eq vendorUserId) and
                        VehicleAssignmentsTable.endedAt.isNull()
                }.firstOrNull()
                ?.toVehicleAssignment()
        }
    }

    override suspend fun endAssignment(
        id: Int,
        endedAt: Instant,
    ) {
        VehicleAssignmentsTable.update(where = { VehicleAssignmentsTable.id eq id }) {
            it[VehicleAssignmentsTable.endedAt] = endedAt
        }
    }
}
