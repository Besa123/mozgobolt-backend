/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationRepository
import com.mozgobolt.feature.vehicleTracking.domain.model.ActiveVehicleLocationAssignmentSnapshot
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationStateSnapshot
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private val MIN_PERSISTABLE_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z")
private val MAX_PERSISTABLE_TIMESTAMP = Instant.parse("+294275-12-31T23:59:59.999999Z")

private data class AssignmentReference(
    val vehicleId: Int,
    val vendorUserId: Int,
    val startedAt: Instant,
    val endedAt: Instant?,
)

class VehicleLocationRepositoryI : VehicleLocationRepository {
    override suspend fun findLiveStateSnapshot(
        cutoff: Instant,
        asOf: Instant,
    ): VehicleLocationStateSnapshot {
        require(!cutoff.isAfter(asOf)) { "Live-location snapshot cutoff must not be after its as-of time" }

        val activeAssignments = activeLocationAssignments()
        val latestLocations =
            activeAssignments
                .associateBy(ActiveVehicleLocationAssignmentSnapshot::assignmentId)
                .let { latestLocations(cutoff, asOf, it) }

        return VehicleLocationStateSnapshot(
            activeAssignments = activeAssignments,
            latestLocations = latestLocations,
        )
    }

    private fun activeLocationAssignments() =
        VehicleAssignmentsTable
            .select(
                VehicleAssignmentsTable.id,
                VehicleAssignmentsTable.vehicleId,
                VehicleAssignmentsTable.vendorUserId,
                VehicleAssignmentsTable.startedAt,
                VehicleAssignmentsTable.locationSharingConsentedAt,
            ).where { VehicleAssignmentsTable.endedAt.isNull() }
            .map { row ->
                ActiveVehicleLocationAssignmentSnapshot(
                    vehicleId = row[VehicleAssignmentsTable.vehicleId],
                    assignmentId = row[VehicleAssignmentsTable.id].value,
                    vendorUserId = row[VehicleAssignmentsTable.vendorUserId],
                    startedAt = row[VehicleAssignmentsTable.startedAt],
                    sharesLocationWithCustomers = row[VehicleAssignmentsTable.locationSharingConsentedAt] != null,
                )
            }

    private fun latestLocations(
        cutoff: Instant,
        asOf: Instant,
        activeAssignmentsById: Map<Int, ActiveVehicleLocationAssignmentSnapshot>,
    ): List<VehicleLocationUpdate> {
        if (activeAssignmentsById.values.none(ActiveVehicleLocationAssignmentSnapshot::sharesLocationWithCustomers)) {
            return emptyList()
        }

        val activeConsentedAssignments =
            VehicleAssignmentsTable
                .select(VehicleAssignmentsTable.id)
                .where {
                    VehicleAssignmentsTable.endedAt.isNull() and
                        VehicleAssignmentsTable.locationSharingConsentedAt.isNotNull()
                }
        return VehicleLocationsTable
            .select(
                VehicleLocationsTable.vehicleId,
                VehicleLocationsTable.vendorUserId,
                VehicleLocationsTable.assignmentId,
                VehicleLocationsTable.latitude,
                VehicleLocationsTable.longitude,
                VehicleLocationsTable.recordedAt,
                VehicleLocationsTable.cellId,
                VehicleLocationsTable.idempotencyKey,
            ).where {
                (VehicleLocationsTable.assignmentId inSubQuery activeConsentedAssignments) and
                    (VehicleLocationsTable.recordedAt greaterEq cutoff) and
                    (VehicleLocationsTable.recordedAt lessEq asOf)
            }.withDistinctOn(
                VehicleLocationsTable.assignmentId to SortOrder.ASC,
                VehicleLocationsTable.recordedAt to SortOrder.DESC,
            ).map { row -> row.toLiveLocationUpdate(activeAssignmentsById) }
            .filter { update ->
                update.recordedAt >= activeAssignmentsById.getValue(checkNotNull(update.assignmentId)).startedAt
            }
    }

    private fun ResultRow.toLiveLocationUpdate(
        activeAssignmentsById: Map<Int, ActiveVehicleLocationAssignmentSnapshot>,
    ): VehicleLocationUpdate {
        val assignmentId =
            checkNotNull(this[VehicleLocationsTable.assignmentId]) {
                "A consented assignment location must have an assignment id"
            }
        val assignment =
            checkNotNull(activeAssignmentsById[assignmentId]) {
                "A live-location snapshot row must belong to a current assignment"
            }
        val vendorUserId = checkNotNull(this[VehicleLocationsTable.vendorUserId])
        check(
            this[VehicleLocationsTable.vehicleId] == assignment.vehicleId &&
                vendorUserId == assignment.vendorUserId,
        ) {
            "A live-location snapshot row must match its current assignment"
        }

        return VehicleLocationUpdate(
            vehicleId = assignment.vehicleId,
            vendorUserId = assignment.vendorUserId,
            latitude = this[VehicleLocationsTable.latitude].toDouble(),
            longitude = this[VehicleLocationsTable.longitude].toDouble(),
            recordedAt = this[VehicleLocationsTable.recordedAt],
            cellId = CellId(this[VehicleLocationsTable.cellId]),
            sharesLocationWithCustomers = true,
            assignmentId = assignment.assignmentId,
            idempotencyKey = UUID.fromString(this[VehicleLocationsTable.idempotencyKey]),
        )
    }

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
