package com.mozgobolt.feature.vehiclePing.domain.model

import java.time.Instant

data class VehiclePing(
    val id: Long,
    val vehicleId: Int,
    val buyerUserId: Int,
    val sentAt: Instant,
)
