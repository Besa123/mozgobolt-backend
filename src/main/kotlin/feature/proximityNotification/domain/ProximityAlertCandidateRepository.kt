/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.domain

import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation

/** Loads saved locations belonging to buyers who favorited a company's vehicle. */
interface ProximityAlertCandidateRepository {
    /** Returns each saved location owned by a user who favorited [companyId]. */
    suspend fun findSavedLocationsForCompany(companyId: Int): List<UserSavedLocation>
}
