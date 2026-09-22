package com.mozgobolt.feature.vehicleTracking.routing.dto.response

import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.serialization.Serializable

@Serializable
data class VehicleLocationResponseDto(
    val vehicleId: Int,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: String,
)

fun VehicleLocationUpdate.toResponseDto() =
    VehicleLocationResponseDto(
        vehicleId = vehicleId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt.toString(),
    )
