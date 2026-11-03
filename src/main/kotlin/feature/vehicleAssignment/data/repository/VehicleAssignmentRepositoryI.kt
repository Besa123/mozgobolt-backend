/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.data.repository

import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentEntity
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleAssignment.data.mapper.toVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class VehicleAssignmentRepositoryI : VehicleAssignmentRepository {
    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
        VehicleAssignmentsTable
            .selectAll()
            .where {
                (VehicleAssignmentsTable.vendorUserId eq vendorUserId) and
                    VehicleAssignmentsTable.endedAt.isNull()
            }.singleOrNull()
            ?.toVehicleAssignment()

    override suspend fun findActiveDetailsForVendor(vendorUserId: Int): ActiveVehicleAssignment? =
        (VehicleAssignmentsTable innerJoin VehiclesTable)
            .select(
                VehicleAssignmentsTable.id,
                VehicleAssignmentsTable.vehicleId,
                VehiclesTable.companyId,
                VehiclesTable.label,
                VehiclesTable.licensePlate,
                VehicleAssignmentsTable.locationSharingConsentedAt,
            ).where {
                (VehicleAssignmentsTable.vendorUserId eq vendorUserId) and
                    VehicleAssignmentsTable.endedAt.isNull()
            }.limit(1)
            .map { row ->
                ActiveVehicleAssignment(
                    id = row[VehicleAssignmentsTable.id].value,
                    vehicleId = row[VehicleAssignmentsTable.vehicleId],
                    companyId = row[VehiclesTable.companyId],
                    vehicleLabel = row[VehiclesTable.label],
                    licensePlate = row[VehiclesTable.licensePlate],
                    sharesLocationWithCustomers =
                        row[VehicleAssignmentsTable.locationSharingConsentedAt] != null,
                )
            }.firstOrNull()

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
        VehicleAssignmentsTable
            .selectAll()
            .where {
                (VehicleAssignmentsTable.vehicleId eq vehicleId) and
                    VehicleAssignmentsTable.endedAt.isNull()
            }.singleOrNull()
            ?.toVehicleAssignment()

    override suspend fun findAllForCompany(companyId: Int): List<VehicleAssignment> =
        (VehicleAssignmentsTable innerJoin VehiclesTable)
            .selectAll()
            .where { VehiclesTable.companyId eq companyId }
            .orderBy(VehicleAssignmentsTable.id to SortOrder.ASC)
            .map { it.toVehicleAssignment() }

    override suspend fun findStaleActiveBefore(
        cutoff: Instant,
        limit: Int,
    ): List<VehicleAssignment> =
        VehicleAssignmentsTable
            .selectAll()
            .where {
                VehicleAssignmentsTable.endedAt.isNull() and
                    (
                        (
                            VehicleAssignmentsTable.lastTelemetryAt.isNull() and
                                (VehicleAssignmentsTable.startedAt less cutoff)
                        ) or
                            (VehicleAssignmentsTable.lastTelemetryAt less cutoff)
                    )
            }.orderBy(VehicleAssignmentsTable.id to SortOrder.ASC)
            .limit(limit)
            .forUpdate()
            .map { it.toVehicleAssignment() }

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
    ): VehicleAssignment? = startAssignment(vehicleId, vendorUserId, startedAt, locationSharingConsentedAt = null)

    override suspend fun startAssignment(
        vehicleId: Int,
        vendorUserId: Int,
        startedAt: Instant,
        locationSharingConsentedAt: Instant?,
    ): VehicleAssignment? {
        val insertedCount =
            VehicleAssignmentsTable
                .insertIgnore {
                    it[VehicleAssignmentsTable.vehicleId] = vehicleId
                    it[VehicleAssignmentsTable.vendorUserId] = vendorUserId
                    it[VehicleAssignmentsTable.startedAt] = startedAt
                    it[VehicleAssignmentsTable.locationSharingConsentedAt] = locationSharingConsentedAt
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

    override suspend fun recordTelemetryReceived(
        assignmentId: Int,
        vendorUserId: Int,
        receivedAt: Instant,
    ): Boolean {
        val updated =
            VehicleAssignmentsTable.update(
                where = {
                    (VehicleAssignmentsTable.id eq assignmentId) and
                        (VehicleAssignmentsTable.vendorUserId eq vendorUserId) and
                        VehicleAssignmentsTable.endedAt.isNull()
                },
            ) {
                it[VehicleAssignmentsTable.lastTelemetryAt] = receivedAt
            }
        return updated == 1
    }

    override suspend fun endAssignment(
        id: Int,
        endedAt: Instant,
    ): Boolean {
        val updated =
            VehicleAssignmentsTable.update(
                where = {
                    (VehicleAssignmentsTable.id eq id) and
                        VehicleAssignmentsTable.endedAt.isNull()
                },
            ) {
                it[VehicleAssignmentsTable.endedAt] = endedAt
            }
        return updated == 1
    }
}
