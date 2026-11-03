/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingOutcome

/** Use cases for recording buyer pings and notifying the assigned vendor. */
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
    ): AppResult<VehiclePingOutcome, PingError>

    /** Delivers the transient notification only after the transaction persisting [outcome.ping] commits. */
    suspend fun dispatchPing(outcome: VehiclePingOutcome)
}
