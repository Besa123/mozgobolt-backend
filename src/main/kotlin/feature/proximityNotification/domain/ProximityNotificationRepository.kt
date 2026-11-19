/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.domain

import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationCandidate
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
     * Atomically claims a bounded batch of entry reminders. Returns saved-location IDs that are new,
     * outside, or past cooldown; currently-inside locations still within cooldown are omitted.
     * The caller owns the transaction and may send pushes only after it commits.
     */
    suspend fun claimEntryNotifications(
        vehicleId: Int,
        candidates: List<ProximityNotificationCandidate>,
        attemptedAt: Instant,
        cooldownCutoff: Instant,
    ): Set<Int>

    /**
     * Marks every record of [vehicleId] at one of [savedLocationIds] that is still inside as no
     * longer inside, in a single statement, without touching
     * [ProximityNotificationRecord.lastAttemptedAt] — so the next entry is judged against the last
     * attempt, not when the vehicle happened to leave. Records that are already outside, or that
     * do not exist yet, are left alone (nothing to mark), and an empty [savedLocationIds] does not
     * touch the database. One statement for all locations keeps a telemetry point's cost
     * independent of how many buyers watch the vehicle's company.
     */
    suspend fun markOutside(
        vehicleId: Int,
        savedLocationIds: Collection<Int>,
    )

    /** Removes notification state for a buyer and company and returns the number removed. */
    suspend fun deleteForUserAndCompany(
        buyerUserId: Int,
        companyId: Int,
    ): Int
}
