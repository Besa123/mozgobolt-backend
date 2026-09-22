package com.mozgobolt.feature.vehiclePing.data.mapper

import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingEntity
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing

fun VehiclePingEntity.toVehiclePing() =
    VehiclePing(
        id = id.value,
        vehicleId = vehicleId,
        buyerUserId = buyerUserId,
        sentAt = sentAt,
    )
