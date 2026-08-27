package com.shelflife.feature.storageLocation.domain

import com.shelflife.feature.storageLocation.domain.model.RenameLocationOutcome
import com.shelflife.feature.storageLocation.domain.model.StorageLocation

interface StorageLocationRepository {
    suspend fun findByIdAndUserId(
        id: Int,
        userId: Int,
    ): StorageLocation?

    suspend fun findAllByUserId(userId: Int): List<StorageLocation>

    suspend fun create(
        userId: Int,
        name: String,
    ): StorageLocation?

    suspend fun updateName(
        id: Int,
        userId: Int,
        name: String,
    ): RenameLocationOutcome

    suspend fun deleteByIdAndUserId(
        id: Int,
        userId: Int,
    ): Boolean
}
