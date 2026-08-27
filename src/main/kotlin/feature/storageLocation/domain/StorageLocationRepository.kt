package com.shelflife.feature.storageLocation.domain

import com.shelflife.feature.storageLocation.domain.model.StorageLocation

interface StorageLocationRepository {
    suspend fun findByIdAndUserId(
        id: Int,
        userId: Int,
    ): StorageLocation?

    suspend fun findAllByUserId(userId: Int): List<StorageLocation>

    suspend fun existsByUserIdAndName(
        userId: Int,
        name: String,
    ): Boolean

    suspend fun create(
        userId: Int,
        name: String,
    ): StorageLocation

    suspend fun updateName(
        id: Int,
        userId: Int,
        name: String,
    ): StorageLocation?

    suspend fun deleteByIdAndUserId(
        id: Int,
        userId: Int,
    ): Boolean
}
