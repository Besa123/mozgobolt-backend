/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.data.database

import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** Cooldown state only: the composite key keeps the latest ping per buyer/vehicle pair. */
data object VehiclePingsTable : Table("vehicle_pings") {
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
    override val primaryKey = PrimaryKey(vehicleId, buyerUserId)

    init {
        index(false, buyerUserId)
        index(false, sentAt)
    }
}
