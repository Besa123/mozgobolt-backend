package com.shelflife.feature.storageLocation.data.repository

import com.shelflife.feature.product.data.database.StorageLocationEntity
import com.shelflife.feature.product.data.database.StorageLocationsTable
import com.shelflife.feature.storageLocation.data.mapper.toStorageLocation
import com.shelflife.feature.storageLocation.domain.StorageLocationRepository
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.update

class StorageLocationRepositoryI : StorageLocationRepository {
    override suspend fun findByIdAndUserId(
        id: Int,
        userId: Int,
    ): StorageLocation? =
        StorageLocationEntity
            .find { (StorageLocationsTable.id eq id) and (StorageLocationsTable.userId eq userId) }
            .limit(1)
            .firstOrNull()
            ?.toStorageLocation()

    override suspend fun findAllByUserId(userId: Int): List<StorageLocation> =
        StorageLocationEntity
            .find { StorageLocationsTable.userId eq userId }
            .orderBy(StorageLocationsTable.name to SortOrder.ASC)
            .map { it.toStorageLocation() }

    override suspend fun existsByUserIdAndName(
        userId: Int,
        name: String,
    ): Boolean =
        StorageLocationEntity
            .find { (StorageLocationsTable.userId eq userId) and (StorageLocationsTable.name eq name) }
            .limit(1)
            .firstOrNull() != null

    override suspend fun create(
        userId: Int,
        name: String,
    ): StorageLocation {
        val id =
            StorageLocationsTable
                .insertAndGetId {
                    it[StorageLocationsTable.userId] = userId
                    it[StorageLocationsTable.name] = name
                }.value

        return StorageLocationEntity[id].toStorageLocation()
    }

    override suspend fun updateName(
        id: Int,
        userId: Int,
        name: String,
    ): StorageLocation? {
        val updatedCount =
            StorageLocationsTable.update(
                where = { (StorageLocationsTable.id eq id) and (StorageLocationsTable.userId eq userId) },
            ) {
                it[StorageLocationsTable.name] = name
            }

        return if (updatedCount > 0) {
            StorageLocationEntity[id].toStorageLocation()
        } else {
            null
        }
    }

    override suspend fun deleteByIdAndUserId(
        id: Int,
        userId: Int,
    ): Boolean {
        val entity =
            StorageLocationEntity
                .find {
                    (StorageLocationsTable.id eq id) and (StorageLocationsTable.userId eq userId)
                }.firstOrNull() ?: return false

        entity.delete()
        return true
    }
}
