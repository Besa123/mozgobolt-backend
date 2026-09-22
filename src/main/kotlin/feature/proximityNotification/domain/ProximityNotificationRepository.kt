package com.mozgobolt.feature.proximityNotification.domain

import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationRecord
import java.time.Instant

interface ProximityNotificationRepository {
    suspend fun find(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    ): ProximityNotificationRecord?

    /**
     * Creates this (buyer, saved location, vehicle) triple's first record, or updates an
     * existing one — either way leaving [ProximityNotificationRecord.currentlyInside] `true` and
     * [ProximityNotificationRecord.lastNotifiedAt] set to [notifiedAt]. The caller has already
     * decided a push should fire before calling this; it only persists that decision.
     */
    suspend fun recordEntryNotified(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
        notifiedAt: Instant,
    ): ProximityNotificationRecord

    /**
     * Marks an existing triple as no longer inside, without touching
     * [ProximityNotificationRecord.lastNotifiedAt] — so the next entry is judged against when the
     * buyer was actually last notified, not when the vehicle happened to leave. A no-op if no
     * record exists yet for this triple (nothing to mark).
     */
    suspend fun markOutside(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    )
}
