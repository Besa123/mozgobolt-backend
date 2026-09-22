package com.mozgobolt.feature.savedLocation.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation

interface SavedLocationService {
    suspend fun createSavedLocation(
        userId: Int,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): UserSavedLocation

    suspend fun listSavedLocations(userId: Int): List<UserSavedLocation>

    suspend fun deleteSavedLocation(
        userId: Int,
        savedLocationId: Int,
    ): AppResult<Unit, SavedLocationError>
}
