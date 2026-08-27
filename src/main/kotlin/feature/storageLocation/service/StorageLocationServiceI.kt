package com.shelflife.feature.storageLocation.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.storageLocation.domain.StorageLocationRepository
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import com.shelflife.feature.storageLocation.domain.model.RenameLocationOutcome
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError

class StorageLocationServiceI(
    private val storageLocationRepository: StorageLocationRepository,
    private val tx: TransactionalRunner,
) : StorageLocationService {
    override suspend fun listByUserId(userId: Int): List<StorageLocation> =
        tx.transactional {
            storageLocationRepository.findAllByUserId(userId)
        }

    override suspend fun createForUser(
        userId: Int,
        name: String,
    ): AppResult<StorageLocation, StorageLocationError> {
        val normalizedName = name.trim()

        val created =
            tx.transactional {
                if (storageLocationRepository.existsByUserIdAndName(userId, normalizedName)) {
                    return@transactional null
                }

                storageLocationRepository.create(userId, normalizedName)
            } ?: return AppResult.Error(StorageLocationError.DUPLICATE_NAME)

        return AppResult.Success(created)
    }

    override suspend fun renameLocation(
        userId: Int,
        locationId: Int,
        newName: String,
    ): AppResult<StorageLocation, StorageLocationError> {
        val normalizedName = newName.trim()

        val outcome =
            tx.transactional {
                if (storageLocationRepository.findByIdAndUserId(locationId, userId) == null) {
                    return@transactional RenameLocationOutcome.NotFound
                }

                if (storageLocationRepository.existsByUserIdAndName(userId, normalizedName)) {
                    return@transactional RenameLocationOutcome.DuplicateName
                }

                storageLocationRepository
                    .updateName(locationId, userId, normalizedName)
                    ?.let { RenameLocationOutcome.Renamed(it) }
                    ?: RenameLocationOutcome.NotFound
            }

        return when (outcome) {
            is RenameLocationOutcome.Renamed ->
                AppResult.Success(outcome.location)

            is RenameLocationOutcome.NotFound ->
                AppResult.Error(StorageLocationError.NOT_FOUND)

            is RenameLocationOutcome.DuplicateName ->
                AppResult.Error(StorageLocationError.DUPLICATE_NAME)
        }
    }

    override suspend fun deleteLocation(
        userId: Int,
        locationId: Int,
    ): AppResult<Unit, StorageLocationError> {
        val deleted =
            tx.transactional {
                storageLocationRepository.deleteByIdAndUserId(locationId, userId)
            }

        return if (deleted) {
            AppResult.Success(Unit)
        } else {
            AppResult.Error(StorageLocationError.NOT_FOUND)
        }
    }
}
