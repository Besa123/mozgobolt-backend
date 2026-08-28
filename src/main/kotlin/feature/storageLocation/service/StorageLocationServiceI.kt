package com.shelflife.feature.storageLocation.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.storageLocation.domain.StorageLocationRepository
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import com.shelflife.feature.storageLocation.domain.model.RenameLocationOutcome
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError
import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncOperation

class StorageLocationServiceI(
    private val storageLocationRepository: StorageLocationRepository,
    private val syncService: SyncService,
    private val tx: TransactionalRunner,
) : StorageLocationService {
    override suspend fun listByUserId(userId: Int): List<StorageLocation> =
        tx.transactional {
            storageLocationRepository.findAllByUserId(userId)
        }

    override suspend fun createForUser(
        userId: Int,
        name: String,
        originDeviceId: String?,
    ): AppResult<StorageLocation, StorageLocationError> {
        val normalizedName = name.trim()

        val created =
            tx.transactional {
                storageLocationRepository.create(userId, normalizedName)?.also {
                    syncService.recordChange(
                        userId,
                        SyncEntityType.STORAGE_LOCATION,
                        it.id,
                        SyncOperation.UPSERT,
                        originDeviceId,
                    )
                }
            } ?: return AppResult.Error(StorageLocationError.DUPLICATE_NAME)

        return AppResult.Success(created)
    }

    override suspend fun renameLocation(
        userId: Int,
        locationId: Int,
        newName: String,
        originDeviceId: String?,
    ): AppResult<StorageLocation, StorageLocationError> {
        val normalizedName = newName.trim()

        val outcome =
            tx.transactional {
                if (storageLocationRepository.findByIdAndUserId(locationId, userId) == null) {
                    return@transactional RenameLocationOutcome.NotFound
                }

                storageLocationRepository.updateName(locationId, userId, normalizedName).also { renamed ->
                    if (renamed is RenameLocationOutcome.Renamed) {
                        syncService.recordChange(
                            userId,
                            SyncEntityType.STORAGE_LOCATION,
                            locationId,
                            SyncOperation.UPSERT,
                            originDeviceId,
                        )
                    }
                }
            }

        return when (outcome) {
            is RenameLocationOutcome.Renamed -> AppResult.Success(outcome.location)
            is RenameLocationOutcome.NotFound -> AppResult.Error(StorageLocationError.NOT_FOUND)
            is RenameLocationOutcome.DuplicateName -> AppResult.Error(StorageLocationError.DUPLICATE_NAME)
        }
    }

    override suspend fun deleteLocation(
        userId: Int,
        locationId: Int,
        originDeviceId: String?,
    ): AppResult<Unit, StorageLocationError> {
        val deleted =
            tx.transactional {
                storageLocationRepository.deleteByIdAndUserId(locationId, userId).also { wasDeleted ->
                    if (wasDeleted) {
                        syncService.recordChange(
                            userId,
                            SyncEntityType.STORAGE_LOCATION,
                            locationId,
                            SyncOperation.DELETE,
                            originDeviceId,
                        )
                    }
                }
            }

        return if (deleted) {
            AppResult.Success(Unit)
        } else {
            AppResult.Error(StorageLocationError.NOT_FOUND)
        }
    }
}
