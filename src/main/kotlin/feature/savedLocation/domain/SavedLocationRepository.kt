package com.mozgobolt.feature.savedLocation.domain

import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation

interface SavedLocationRepository {
    suspend fun create(
        userId: Int,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): UserSavedLocation

    suspend fun findById(id: Int): UserSavedLocation?

    suspend fun findAllForUser(userId: Int): List<UserSavedLocation>

    suspend fun delete(id: Int)

    /**
     * All saved locations belonging to any of [userIds] — the second half of the
     * candidate-narrowing query [com.mozgobolt.feature.proximityNotification.service.ProximityAlertServiceI]
     * relies on. Backed by the existing index on `user_saved_locations(user_id)`. Empty input
     * returns an empty list without touching the database.
     */
    suspend fun findAllForUsers(userIds: Collection<Int>): List<UserSavedLocation>
}
