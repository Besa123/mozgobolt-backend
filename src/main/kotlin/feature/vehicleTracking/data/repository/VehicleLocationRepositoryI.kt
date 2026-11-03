/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import java.math.BigDecimal
import java.time.Instant

private val MIN_PERSISTABLE_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z")
private val MAX_PERSISTABLE_TIMESTAMP = Instant.parse("+294275-12-31T23:59:59.999999Z")

private data class AssignmentReference(
    val vehicleId: Int,
    val vendorUserId: Int,
    val startedAt: Instant,
    val endedAt: Instant?,
)

class VehicleLocationRepositoryI : VehicleLocationRepository {
    override suspend fun insertAll(updates: List<VehicleLocationUpdate>) {
        if (updates.isEmpty()) return

        insertRows(updates)
    }

    override suspend fun insertAllForCurrentReferences(updates: List<VehicleLocationUpdate>): Int {
        if (updates.isEmpty()) return 0

        val assignmentIds = updates.mapNotNull(VehicleLocationUpdate::assignmentId).toSet()
        val assignmentsById =
            if (assignmentIds.isEmpty()) {
                emptyMap()
            } else {
                VehicleAssignmentsTable
                    .select(
                        VehicleAssignmentsTable.id,
                        VehicleAssignmentsTable.vehicleId,
                        VehicleAssignmentsTable.vendorUserId,
                        VehicleAssignmentsTable.startedAt,
                        VehicleAssignmentsTable.endedAt,
                    ).where { VehicleAssignmentsTable.id inList assignmentIds }
                    .associate { row ->
                        row[VehicleAssignmentsTable.id].value to
                            AssignmentReference(
                                vehicleId = row[VehicleAssignmentsTable.vehicleId],
                                vendorUserId = row[VehicleAssignmentsTable.vendorUserId],
                                startedAt = row[VehicleAssignmentsTable.startedAt],
                                endedAt = row[VehicleAssignmentsTable.endedAt],
                            )
                    }
            }

        val unassignedUpdates = updates.filter { it.assignmentId == null }
        val vehicleIds = unassignedUpdates.map(VehicleLocationUpdate::vehicleId).toSet()
        val existingVehicleIds =
            if (vehicleIds.isEmpty()) {
                emptySet()
            } else {
                VehiclesTable
                    .select(VehiclesTable.id)
                    .where { VehiclesTable.id inList vehicleIds }
                    .map { it[VehiclesTable.id].value }
                    .toSet()
            }
        val vendorUserIds = unassignedUpdates.map(VehicleLocationUpdate::vendorUserId).toSet()
        val existingVendorUserIds =
            if (vendorUserIds.isEmpty()) {
                emptySet()
            } else {
                UsersTable
                    .select(UsersTable.id)
                    .where { UsersTable.id inList vendorUserIds }
                    .map { it[UsersTable.id].value }
                    .toSet()
            }

        val currentUpdates =
            updates.filter { update ->
                update.hasPersistableFields() &&
                    if (update.assignmentId == null) {
                        update.vehicleId in existingVehicleIds && update.vendorUserId in existingVendorUserIds
                    } else {
                        assignmentsById[update.assignmentId]?.let { assignment ->
                            assignment.vehicleId == update.vehicleId &&
                                assignment.vendorUserId == update.vendorUserId &&
                                !update.recordedAt.isBefore(assignment.startedAt) &&
                                (assignment.endedAt == null || !update.recordedAt.isAfter(assignment.endedAt))
                        } == true
                    }
            }

        insertRows(currentUpdates)
        return updates.size - currentUpdates.size
    }

    private fun insertRows(updates: List<VehicleLocationUpdate>) {
        if (updates.isEmpty()) return

        VehicleLocationsTable.batchInsert(updates, ignore = true) { update ->
            this[VehicleLocationsTable.vehicleId] = update.vehicleId
            this[VehicleLocationsTable.vendorUserId] = update.vendorUserId
            this[VehicleLocationsTable.assignmentId] = update.assignmentId
            this[VehicleLocationsTable.latitude] = BigDecimal.valueOf(update.latitude)
            this[VehicleLocationsTable.longitude] = BigDecimal.valueOf(update.longitude)
            this[VehicleLocationsTable.recordedAt] = update.recordedAt
            this[VehicleLocationsTable.cellId] = update.cellId.value
            this[VehicleLocationsTable.idempotencyKey] = update.idempotencyKey.toString()
        }
    }

    private fun VehicleLocationUpdate.hasPersistableFields(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in GeoBounds.MIN_LATITUDE..GeoBounds.MAX_LATITUDE &&
            longitude in GeoBounds.MIN_LONGITUDE..GeoBounds.MAX_LONGITUDE &&
            recordedAt in MIN_PERSISTABLE_TIMESTAMP..MAX_PERSISTABLE_TIMESTAMP &&
            cellId.value.length <= H3CellIndexer.CELL_ADDRESS_MAX_LENGTH

    override suspend fun purgeOlderThan(cutoff: Instant): Int {
        val staleAssignments =
            VehicleAssignmentsTable
                .select(VehicleAssignmentsTable.id)
                .where {
                    VehicleAssignmentsTable.endedAt.isNotNull() and (VehicleAssignmentsTable.endedAt less cutoff)
                }

        // A future "retain this session" opt-out (e.g. a flag on vehicle_assignments) would just
        // need one more AND clause here — not built yet, per product decision.
        return VehicleLocationsTable.deleteWhere {
            (assignmentId inSubQuery staleAssignments) or
                (assignmentId.isNull() and (recordedAt less cutoff))
        }
    }
}
