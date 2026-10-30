package com.mozgobolt.feature.proximityNotification.data.database

import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationsTable
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object ProximityNotificationsTable : IntIdTable("proximity_notifications") {
    val buyerUserId =
        integer("buyer_user_id").references(
            UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val savedLocationId =
        integer("saved_location_id").references(
            UserSavedLocationsTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val vehicleId =
        integer("vehicle_id").references(
            VehiclesTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val currentlyInside = bool("currently_inside")
    val lastNotifiedAt = timestamp("last_notified_at")

    init {
        uniqueIndex(buyerUserId, savedLocationId, vehicleId)
        index(false, savedLocationId)
        index(false, vehicleId)
    }
}

class ProximityNotificationEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var buyerUserId by ProximityNotificationsTable.buyerUserId
    var savedLocationId by ProximityNotificationsTable.savedLocationId
    var vehicleId by ProximityNotificationsTable.vehicleId
    var currentlyInside by ProximityNotificationsTable.currentlyInside
    var lastNotifiedAt by ProximityNotificationsTable.lastNotifiedAt

    companion object : IntEntityClass<ProximityNotificationEntity>(ProximityNotificationsTable)
}
