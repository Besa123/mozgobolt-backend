/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.data.database

import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp
import java.math.BigDecimal

data object VehicleLocationsTable : LongIdTable("vehicle_locations") {
    val vehicleId =
        integer("vehicle_id").references(
            VehiclesTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )

    // The V1 user-delete trigger removes assigned rows and nulls only unassigned history.
    val vendorUserId =
        integer("vendor_user_id")
            .references(
                UsersTable.id,
                onDelete = ReferenceOption.NO_ACTION,
                onUpdate = ReferenceOption.RESTRICT,
            ).nullable()

    // Which driving session this point was recorded during — backs VehicleLocationRepositoryI's
    // retention purge, which scopes its 30-day cutoff to a session's ended_at, not each row's own
    // recordedAt.
    val assignmentId = integer("assignment_id").nullable().index()
    val latitude = decimal("latitude", 9, 6)
    val longitude = decimal("longitude", 9, 6)
    val recordedAt = timestamp("recorded_at")

    // Persist the ingestion-time H3 cell so a future historical proximity query can index it
    // without recomputing every stored coordinate.
    val cellId = varchar("cell_id", H3CellIndexer.CELL_ADDRESS_MAX_LENGTH)
    val idempotencyKey = varchar("idempotency_key", 36).uniqueIndex()

    init {
        check("vehicle_locations_latitude_range_check") {
            (latitude greaterEq BigDecimal.valueOf(GeoBounds.MIN_LATITUDE)) and
                (latitude lessEq BigDecimal.valueOf(GeoBounds.MAX_LATITUDE))
        }
        check("vehicle_locations_longitude_range_check") {
            (longitude greaterEq BigDecimal.valueOf(GeoBounds.MIN_LONGITUDE)) and
                (longitude lessEq BigDecimal.valueOf(GeoBounds.MAX_LONGITUDE))
        }
        check("vehicle_locations_assignment_vendor_required_check") {
            assignmentId.isNull() or vendorUserId.isNotNull()
        }
        foreignKey(
            assignmentId to VehicleAssignmentsTable.id,
            vehicleId to VehicleAssignmentsTable.vehicleId,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
        foreignKey(
            assignmentId to VehicleAssignmentsTable.id,
            vendorUserId to VehicleAssignmentsTable.vendorUserId,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    }
}
