/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.data.database

import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object VehicleAssignmentsTable : IntIdTable("vehicle_assignments") {
    val vehicleId =
        integer("vehicle_id")
            .references(
                VehiclesTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).index()
    val vendorUserId =
        integer("vendor_user_id")
            .references(
                UsersTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).index()
    val startedAt = timestamp("started_at")
    val endedAt = timestamp("ended_at").nullable()
    val locationSharingConsentedAt = timestamp("location_sharing_consented_at").nullable()
    val lastTelemetryAt = timestamp("last_telemetry_at").nullable()

    init {
        uniqueIndex(id, vehicleId)
        uniqueIndex(id, vendorUserId)
        uniqueIndex(
            vendorUserId,
            filterCondition = { endedAt.isNull() },
        )
        uniqueIndex(
            vehicleId,
            filterCondition = { endedAt.isNull() },
        )
        index(
            null,
            false,
            endedAt,
            filterCondition = { endedAt.isNotNull() },
        )
    }
}

class VehicleAssignmentEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var vehicleId by VehicleAssignmentsTable.vehicleId
    var vendorUserId by VehicleAssignmentsTable.vendorUserId
    var startedAt by VehicleAssignmentsTable.startedAt
    var endedAt by VehicleAssignmentsTable.endedAt
    var locationSharingConsentedAt by VehicleAssignmentsTable.locationSharingConsentedAt
    var lastTelemetryAt by VehicleAssignmentsTable.lastTelemetryAt

    companion object : IntEntityClass<VehicleAssignmentEntity>(VehicleAssignmentsTable)
}
