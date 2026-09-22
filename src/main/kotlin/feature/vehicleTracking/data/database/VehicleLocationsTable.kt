package com.mozgobolt.feature.vehicleTracking.data.database

import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

data object VehicleLocationsTable : LongIdTable("vehicle_locations") {
    val vehicleId = integer("vehicle_id")
    val vendorUserId = integer("vendor_user_id").nullable()

    // Which driving session this point was recorded during — backs VehicleLocationRepositoryI's
    // retention purge, which scopes its 30-day cutoff to a session's ended_at, not each row's own
    // recordedAt.
    val assignmentId = integer("assignment_id").nullable()
    val latitude = decimal("latitude", 9, 6)
    val longitude = decimal("longitude", 9, 6)
    val recordedAt = timestamp("recorded_at")

    // Denormalized from the coordinate at ingestion time (see VehicleLocationUpdate.cellId) so a
    // future "what was near here" query is an indexed lookup, not a recompute over every
    // historical row.
    val cellId = varchar("cell_id", H3CellIndexer.CELL_ADDRESS_MAX_LENGTH)
}
