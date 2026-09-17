package com.shelflife.feature.storageLocation.data.repository

import com.shelflife.feature.product.data.database.StorageLocationEntity
import com.shelflife.feature.product.data.database.StorageLocationsTable
import com.shelflife.feature.storageLocation.data.mapper.toStorageLocation
import com.shelflife.feature.storageLocation.domain.StorageLocationRepository
import com.shelflife.feature.storageLocation.domain.model.RenameLocationOutcome
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update

private const val POSTGRES_UNIQUE_VIOLATION_SQL_STATE = "23505"

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

    override suspend fun create(
        userId: Int,
        name: String,
    ): StorageLocation? {
        val inserted =
            StorageLocationsTable
                .insertIgnore {
                    it[StorageLocationsTable.userId] = userId
                    it[StorageLocationsTable.name] = name
                }.insertedCount > 0

        if (!inserted) return null

        return StorageLocationEntity
            .find { (StorageLocationsTable.userId eq userId) and (StorageLocationsTable.name eq name) }
            .limit(1)
            .firstOrNull()
            ?.toStorageLocation()
    }

    override suspend fun updateName(
        id: Int,
        userId: Int,
        name: String,
    ): RenameLocationOutcome {
        val updatedCount =
            try {
                StorageLocationsTable.update(
                    where = { (StorageLocationsTable.id eq id) and (StorageLocationsTable.userId eq userId) },
                ) {
                    it[StorageLocationsTable.name] = name
                }
            } catch (e: ExposedSQLException) {
                if (e.sqlState == POSTGRES_UNIQUE_VIOLATION_SQL_STATE) return RenameLocationOutcome.DuplicateName
                throw e
            }

        if (updatedCount == 0) return RenameLocationOutcome.NotFound

        return RenameLocationOutcome.Renamed(StorageLocationEntity[id].toStorageLocation())
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
