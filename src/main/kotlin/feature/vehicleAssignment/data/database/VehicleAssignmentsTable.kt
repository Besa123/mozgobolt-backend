package com.mozgobolt.feature.vehicleAssignment.data.database

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object VehicleAssignmentsTable : IntIdTable("vehicle_assignments") {
    val vehicleId = integer("vehicle_id")
    val vendorUserId = integer("vendor_user_id")
    val startedAt = timestamp("started_at")
    val endedAt = timestamp("ended_at").nullable()
}

class VehicleAssignmentEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var vehicleId by VehicleAssignmentsTable.vehicleId
    var vendorUserId by VehicleAssignmentsTable.vendorUserId
    var startedAt by VehicleAssignmentsTable.startedAt
    var endedAt by VehicleAssignmentsTable.endedAt

    companion object : IntEntityClass<VehicleAssignmentEntity>(VehicleAssignmentsTable)
}
