package com.mozgobolt.feature.savedLocation.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.savedLocation.domain.SavedLocationService
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation

class SavedLocationServiceI(
    private val savedLocationRepository: SavedLocationRepository,
    private val syncService: SyncService,
    private val tx: TransactionalRunner,
) : SavedLocationService {
    override suspend fun createSavedLocation(
        userId: Int,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): UserSavedLocation {
        val trimmedLabel = label.trim()

        return tx.transactional {
            val savedLocation =
                savedLocationRepository.create(
                    userId = userId,
                    label = trimmedLabel,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                )
            syncService.recordChange(
                userId,
                SyncEntityType.USER_SAVED_LOCATION,
                savedLocation.id,
                SyncOperation.UPSERT,
            )

            savedLocation
        }
    }

    override suspend fun listSavedLocations(userId: Int): List<UserSavedLocation> =
        tx.transactional { savedLocationRepository.findAllForUser(userId) }

    @Suppress("ReturnCount")
    override suspend fun deleteSavedLocation(
        userId: Int,
        savedLocationId: Int,
    ): AppResult<Unit, SavedLocationError> =
        tx.transactional {
            val existing =
                savedLocationRepository.findById(savedLocationId)
                    ?: return@transactional AppResult.Error(SavedLocationError.NOT_FOUND)
            // Not-found-shaped, not forbidden-shaped, for someone else's saved location — no
            // reason to confirm to a caller that a given id belongs to another user at all.
            if (existing.userId != userId) return@transactional AppResult.Error(SavedLocationError.NOT_FOUND)

            savedLocationRepository.delete(savedLocationId)
            syncService.recordChange(
                userId,
                SyncEntityType.USER_SAVED_LOCATION,
                savedLocationId,
                SyncOperation.DELETE,
            )

            AppResult.Success(Unit)
        }
}
