package com.shelflife.feature.storageLocation.domain

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError

interface StorageLocationService {
    suspend fun listByUserId(userId: Int): List<StorageLocation>

    suspend fun createForUser(
        userId: Int,
        name: String,
        originDeviceId: String? = null,
    ): AppResult<StorageLocation, StorageLocationError>

    suspend fun renameLocation(
        userId: Int,
        locationId: Int,
        newName: String,
        originDeviceId: String? = null,
    ): AppResult<StorageLocation, StorageLocationError>

    suspend fun deleteLocation(
        userId: Int,
        locationId: Int,
        originDeviceId: String? = null,
    ): AppResult<Unit, StorageLocationError>
}
