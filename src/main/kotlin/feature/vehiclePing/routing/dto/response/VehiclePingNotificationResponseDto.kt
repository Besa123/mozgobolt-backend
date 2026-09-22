package com.mozgobolt.feature.vehiclePing.routing.dto.response

import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import kotlinx.serialization.Serializable

@Serializable
data class VehiclePingNotificationResponseDto(
    val vehicleId: Int,
    val sentAt: String,
    val latitude: Double,
    val longitude: Double,
)

fun VehiclePingNotification.toResponseDto() =
    VehiclePingNotificationResponseDto(
        vehicleId = vehicleId,
        sentAt = sentAt.toString(),
        latitude = latitude,
        longitude = longitude,
    )
