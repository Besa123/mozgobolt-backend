package com.mozgobolt.feature.vehiclePing.domain

import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import java.time.Instant

interface VehiclePingRepository {
    /** `null` if this buyer has never pinged this vehicle before. */
    suspend fun mostRecentSentAt(
        vehicleId: Int,
        buyerUserId: Int,
    ): Instant?

    suspend fun create(
        vehicleId: Int,
        buyerUserId: Int,
        sentAt: Instant,
    ): VehiclePing
}
