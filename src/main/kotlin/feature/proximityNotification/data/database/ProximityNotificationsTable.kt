/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.data.database

import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationsTable
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

data object ProximityNotificationsTable : IntIdTable("proximity_notifications") {
    val buyerUserId =
        integer("buyer_user_id").references(
            UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val savedLocationId =
        integer("saved_location_id")
    val vehicleId =
        integer("vehicle_id").references(
            VehiclesTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val currentlyInside = bool("currently_inside")
    val lastAttemptedAt = timestamp("last_attempted_at")

    init {
        foreignKey(
            savedLocationId to UserSavedLocationsTable.id,
            buyerUserId to UserSavedLocationsTable.userId,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
        uniqueIndex(buyerUserId, savedLocationId, vehicleId)
        index(false, savedLocationId)
        index(false, vehicleId)
    }
}
