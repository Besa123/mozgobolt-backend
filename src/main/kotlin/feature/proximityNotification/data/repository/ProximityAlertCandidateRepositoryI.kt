/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.data.repository

import com.mozgobolt.feature.companyFavorite.data.database.CompanyFavoritesTable
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertCandidateRepository
import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationsTable
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

class ProximityAlertCandidateRepositoryI : ProximityAlertCandidateRepository {
    override suspend fun findSavedLocationsForCompany(companyId: Int): List<UserSavedLocation> =
        CompanyFavoritesTable
            .join(
                otherTable = UserSavedLocationsTable,
                joinType = JoinType.INNER,
                onColumn = CompanyFavoritesTable.userId,
                otherColumn = UserSavedLocationsTable.userId,
            ).select(
                UserSavedLocationsTable.id,
                UserSavedLocationsTable.userId,
                UserSavedLocationsTable.label,
                UserSavedLocationsTable.latitude,
                UserSavedLocationsTable.longitude,
                UserSavedLocationsTable.radiusKm,
                UserSavedLocationsTable.createdAt,
            ).where { CompanyFavoritesTable.companyId eq companyId }
            .map { row ->
                UserSavedLocation(
                    id = row[UserSavedLocationsTable.id].value,
                    userId = row[UserSavedLocationsTable.userId],
                    label = row[UserSavedLocationsTable.label],
                    latitude = row[UserSavedLocationsTable.latitude].toDouble(),
                    longitude = row[UserSavedLocationsTable.longitude].toDouble(),
                    radiusKm = row[UserSavedLocationsTable.radiusKm].toDouble(),
                    createdAt = row[UserSavedLocationsTable.createdAt],
                )
            }
}
