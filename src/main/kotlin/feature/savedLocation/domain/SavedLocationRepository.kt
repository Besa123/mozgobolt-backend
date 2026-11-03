/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.savedLocation.domain

import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation

/** Persistence operations for user-owned saved locations. */
interface SavedLocationRepository {
    /** Creates a saved location for [userId]. */
    suspend fun create(
        userId: Int,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): UserSavedLocation

    /** Finds a saved location by its identifier. */
    suspend fun findById(id: Int): UserSavedLocation?

    /** Lists every saved location owned by [userId]. */
    suspend fun findAllForUser(userId: Int): List<UserSavedLocation>

    /** Returns true only for the transaction that actually removed the row. */
    suspend fun delete(id: Int): Boolean

    /**
     * All saved locations belonging to any of [userIds] — the second half of the
     * candidate-narrowing query [com.mozgobolt.feature.proximityNotification.service.ProximityAlertServiceI]
     * relies on. Backed by the existing index on `user_saved_locations(user_id)`. Empty input
     * returns an empty list without touching the database.
     */
    suspend fun findAllForUsers(userIds: Collection<Int>): List<UserSavedLocation>
}
