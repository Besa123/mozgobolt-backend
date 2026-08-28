package com.shelflife.feature.storageLocation.routing

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError

class FakeStorageLocationService : StorageLocationService {
    val locations = mutableMapOf<Int, MutableList<StorageLocation>>()
    private var nextId = 1

    var listByUserIdResult: List<StorageLocation> = emptyList()
    var createResult: AppResult<StorageLocation, StorageLocationError>? = null
    var renameResult: AppResult<StorageLocation, StorageLocationError>? = null
    var deleteResult: AppResult<Unit, StorageLocationError>? = null

    override suspend fun listByUserId(userId: Int): List<StorageLocation> = locations[userId] ?: emptyList()

    override suspend fun createForUser(
        userId: Int,
        name: String,
        originDeviceId: String?,
    ): AppResult<StorageLocation, StorageLocationError> =
        createResult ?: run {
            val location = StorageLocation(id = nextId++, userId = userId, name = name)
            locations.getOrPut(userId) { mutableListOf() }.add(location)
            AppResult.Success(location)
        }

    override suspend fun renameLocation(
        userId: Int,
        locationId: Int,
        newName: String,
        originDeviceId: String?,
    ): AppResult<StorageLocation, StorageLocationError> =
        renameResult ?: run {
            val location = locations[userId]?.find { it.id == locationId }
            if (location != null) {
                val updated = location.copy(name = newName)
                locations[userId]?.replaceAll { if (it.id == locationId) updated else it }
                AppResult.Success(updated)
            } else {
                AppResult.Error(StorageLocationError.NOT_FOUND)
            }
        }

    override suspend fun deleteLocation(
        userId: Int,
        locationId: Int,
        originDeviceId: String?,
    ): AppResult<Unit, StorageLocationError> =
        deleteResult ?: run {
            val removed = locations[userId]?.removeIf { it.id == locationId } ?: false
            if (removed) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(StorageLocationError.NOT_FOUND)
            }
        }
}
