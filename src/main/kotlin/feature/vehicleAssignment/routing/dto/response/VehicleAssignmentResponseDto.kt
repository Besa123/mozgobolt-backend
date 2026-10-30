package com.mozgobolt.feature.vehicleAssignment.routing.dto.response

import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
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

@Serializable
data class ActiveVehicleAssignmentResponseDto(
    val id: Int,
    val vehicleId: Int,
    val companyId: Int,
    val vehicleLabel: String,
    val licensePlate: String,
)

@Serializable
data class CurrentVendorAssignmentResponseDto(
    val activeAssignment: ActiveVehicleAssignmentResponseDto?,
)

fun ActiveVehicleAssignment.toResponseDto() =
    ActiveVehicleAssignmentResponseDto(
        id = id,
        vehicleId = vehicleId,
        companyId = companyId,
        vehicleLabel = vehicleLabel,
        licensePlate = licensePlate,
    )
