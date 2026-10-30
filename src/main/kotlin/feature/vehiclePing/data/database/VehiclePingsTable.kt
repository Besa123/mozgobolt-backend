package com.mozgobolt.feature.vehiclePing.data.database

import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object VehiclePingsTable : LongIdTable("vehicle_pings") {
    val vehicleId =
        integer("vehicle_id").references(
            VehiclesTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val buyerUserId =
        integer("buyer_user_id").references(
            UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val sentAt = timestamp("sent_at")

    init {
        index(false, vehicleId, buyerUserId, sentAt)
        index(false, buyerUserId)
    }
}

class VehiclePingEntity(
    id: EntityID<Long>,
) : LongEntity(id) {
    var vehicleId by VehiclePingsTable.vehicleId
    var buyerUserId by VehiclePingsTable.buyerUserId
    var sentAt by VehiclePingsTable.sentAt

    companion object : LongEntityClass<VehiclePingEntity>(VehiclePingsTable)
}
