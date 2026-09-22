package com.mozgobolt.feature.vehicleAssignment.data.mapper

import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentEntity
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment

fun VehicleAssignmentEntity.toVehicleAssignment() =
    VehicleAssignment(
        id = id.value,
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        startedAt = startedAt,
        endedAt = endedAt,
    )
