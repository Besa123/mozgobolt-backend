package com.mozgobolt.feature.vehicleAssignment.domain.model

import java.time.Instant

data class VehicleAssignment(
    val id: Int,
    val vehicleId: Int,
    val vendorUserId: Int,
    val startedAt: Instant,
    val endedAt: Instant? = null,
) {
    val isActive: Boolean
        get() = endedAt == null
}
