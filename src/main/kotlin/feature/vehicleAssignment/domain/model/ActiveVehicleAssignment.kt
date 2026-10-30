package com.mozgobolt.feature.vehicleAssignment.domain.model

data class ActiveVehicleAssignment(
    val id: Int,
    val vehicleId: Int,
    val companyId: Int,
    val vehicleLabel: String,
    val licensePlate: String,
)
