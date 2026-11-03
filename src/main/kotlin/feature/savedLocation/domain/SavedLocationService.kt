/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.savedLocation.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation

/** Use cases for managing a user's saved map locations. */
interface SavedLocationService {
    /** Creates a saved location with its alert radius. */
    suspend fun createSavedLocation(
        userId: Int,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): UserSavedLocation

    /** Lists the saved locations belonging to the user. */
    suspend fun listSavedLocations(userId: Int): List<UserSavedLocation>

    /** Deletes one saved location owned by the user. */
    suspend fun deleteSavedLocation(
        userId: Int,
        savedLocationId: Int,
    ): AppResult<Unit, SavedLocationError>
}
