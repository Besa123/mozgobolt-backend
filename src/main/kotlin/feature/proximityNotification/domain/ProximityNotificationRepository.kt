/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.domain

import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationRecord
import java.time.Instant

/** Persistence operations for vehicle-entry notification state and cooldowns. */
interface ProximityNotificationRepository {
    /** Finds the notification state for a buyer, saved location, and vehicle. */
    suspend fun find(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    ): ProximityNotificationRecord?

    /**
     * Unconditionally records an attempted notification for this triple. The timestamp is an
     * attempt time, not proof of push delivery; production eligibility checks use
     * [claimEntryNotification].
     */
    suspend fun recordEntryAttempted(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
        attemptedAt: Instant,
    ): ProximityNotificationRecord

    /**
     * Atomically claims the right to attempt a notification for an entry or elapsed cooldown.
     * Returns `false` when the vehicle is already inside and the saved attempt time is newer than
     * [cooldownCutoff]. A transition from outside always claims, regardless of the cutoff.
     */
    suspend fun claimEntryNotification(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
        attemptedAt: Instant,
        cooldownCutoff: Instant,
    ): Boolean

    /**
     * Marks an existing triple as no longer inside, without touching
     * [ProximityNotificationRecord.lastAttemptedAt] — so the next entry is judged against the
     * last attempt, not when the vehicle happened to leave. A no-op if no
     * record exists yet for this triple (nothing to mark).
     */
    suspend fun markOutside(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    )

    /** Removes notification state for a buyer and company and returns the number removed. */
    suspend fun deleteForUserAndCompany(
        buyerUserId: Int,
        companyId: Int,
    ): Int
}
