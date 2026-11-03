/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.data.mapper

import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentEntity
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import org.jetbrains.exposed.v1.core.ResultRow

fun VehicleAssignmentEntity.toVehicleAssignment() =
    VehicleAssignment(
        id = id.value,
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        startedAt = startedAt,
        endedAt = endedAt,
        locationSharingConsentedAt = locationSharingConsentedAt,
        lastTelemetryAt = lastTelemetryAt,
    )

fun ResultRow.toVehicleAssignment() =
    VehicleAssignment(
        id = this[VehicleAssignmentsTable.id].value,
        vehicleId = this[VehicleAssignmentsTable.vehicleId],
        vendorUserId = this[VehicleAssignmentsTable.vendorUserId],
        startedAt = this[VehicleAssignmentsTable.startedAt],
        endedAt = this[VehicleAssignmentsTable.endedAt],
        locationSharingConsentedAt = this[VehicleAssignmentsTable.locationSharingConsentedAt],
        lastTelemetryAt = this[VehicleAssignmentsTable.lastTelemetryAt],
    )
