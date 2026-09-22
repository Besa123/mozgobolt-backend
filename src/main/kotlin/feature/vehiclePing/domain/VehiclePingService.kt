package com.mozgobolt.feature.vehiclePing.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing

interface VehiclePingService {
    /**
     * [latitude]/[longitude] are the buyer's real, exact position at the moment they tapped
     * ping — this is the only place in the whole ping flow that ever sees that exact value.
     * Every caller past [ping] itself only ever receives the coarsened point.
     */
    suspend fun ping(
        buyerUserId: Int,
        vehicleId: Int,
        latitude: Double,
        longitude: Double,
    ): AppResult<VehiclePing, PingError>
}
