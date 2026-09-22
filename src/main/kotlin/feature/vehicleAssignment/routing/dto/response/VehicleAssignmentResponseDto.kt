package com.mozgobolt.feature.vehicleAssignment.routing.dto.response

import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import kotlinx.serialization.Serializable

@Serializable
data class VehicleAssignmentResponseDto(
    val id: Int,
    val vehicleId: Int,
    val vendorUserId: Int,
)

fun VehicleAssignment.toResponseDto() =
    VehicleAssignmentResponseDto(
        id = id,
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
    )
