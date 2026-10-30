package com.mozgobolt.feature.vehicleTracking.data.database

import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

data object VehicleLocationsTable : LongIdTable("vehicle_locations") {
    val vehicleId =
        integer("vehicle_id").references(
            VehiclesTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val vendorUserId =
        integer("vendor_user_id")
            .references(
                UsersTable.id,
                onDelete = ReferenceOption.SET_NULL,
                onUpdate = ReferenceOption.RESTRICT,
            ).nullable()

    // Which driving session this point was recorded during — backs VehicleLocationRepositoryI's
    // retention purge, which scopes its 30-day cutoff to a session's ended_at, not each row's own
    // recordedAt.
    val assignmentId =
        integer("assignment_id")
            .references(
                VehicleAssignmentsTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).nullable()
            .index()
    val latitude = decimal("latitude", 9, 6)
    val longitude = decimal("longitude", 9, 6)
    val recordedAt = timestamp("recorded_at")

    // Persist the ingestion-time H3 cell so a future historical proximity query can index it
    // without recomputing every stored coordinate.
    val cellId = varchar("cell_id", H3CellIndexer.CELL_ADDRESS_MAX_LENGTH)
}
