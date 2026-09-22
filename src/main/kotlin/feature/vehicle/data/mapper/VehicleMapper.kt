package com.mozgobolt.feature.vehicle.data.mapper

import com.mozgobolt.feature.vehicle.data.database.VehicleEntity
import com.mozgobolt.feature.vehicle.domain.model.Vehicle

fun VehicleEntity.toVehicle() =
    Vehicle(
        id = id.value,
        companyId = companyId,
        label = label,
        licensePlate = licensePlate,
        pictureUrl = pictureUrl,
        createdAt = createdAt,
        archivedAt = archivedAt,
    )
