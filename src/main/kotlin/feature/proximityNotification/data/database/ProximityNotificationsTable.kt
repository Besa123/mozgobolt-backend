package com.mozgobolt.feature.proximityNotification.data.database

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object ProximityNotificationsTable : IntIdTable("proximity_notifications") {
    val buyerUserId = integer("buyer_user_id")
    val savedLocationId = integer("saved_location_id")
    val vehicleId = integer("vehicle_id")
    val currentlyInside = bool("currently_inside")
    val lastNotifiedAt = timestamp("last_notified_at")
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
