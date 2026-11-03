/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.domain

import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import java.time.Instant

/** Persistence operations for ping cooldown state. */
interface VehiclePingRepository {
    /** `null` if this buyer has never pinged this vehicle before. */
    suspend fun lastSentAt(
        vehicleId: Int,
        buyerUserId: Int,
    ): Instant?

    /** Upserts this buyer/vehicle pair's single cooldown-state row and returns its latest state. */
    suspend fun recordLatest(
        vehicleId: Int,
        buyerUserId: Int,
        sentAt: Instant,
    ): VehiclePing

    /** Removes ping-state rows older than [cutoff] and returns the number removed. */
    suspend fun purgeOlderThan(cutoff: Instant): Int
}
