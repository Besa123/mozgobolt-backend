package com.shelflife.feature.pantryEntryImage.data.repository

import com.shelflife.feature.pantryEntryImage.data.database.PantryEntryImagesTable
import com.shelflife.feature.pantryEntryImage.data.mapper.toPantryEntryImage
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageRepository
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageContent
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class PantryEntryImageRepositoryI : PantryEntryImageRepository {
    override suspend fun create(
        pantryEntryId: Int,
        content: PantryEntryImageContent,
    ): PantryEntryImage {
        val createdAt = Instant.now()
        val insertedId =
            PantryEntryImagesTable.insert {
                it[PantryEntryImagesTable.pantryEntryId] = pantryEntryId
                it[storageKey] = content.storageKey
                it[contentType] = content.contentType
                it[sizeBytes] = content.sizeBytes
                it[width] = content.width
                it[height] = content.height
                it[PantryEntryImagesTable.createdAt] = createdAt
            } get PantryEntryImagesTable.id

        return PantryEntryImage(
            id = insertedId.value,
            pantryEntryId = pantryEntryId,
            storageKey = content.storageKey,
            contentType = content.contentType,
            sizeBytes = content.sizeBytes,
            width = content.width,
            height = content.height,
            createdAt = createdAt,
        )
    }

    override suspend fun listByEntryId(entryId: Int): List<PantryEntryImage> =
        PantryEntryImagesTable
            .selectAll()
            .where { PantryEntryImagesTable.pantryEntryId eq entryId }
            .orderBy(PantryEntryImagesTable.id to SortOrder.ASC)
            .map { it.toPantryEntryImage() }

    override suspend fun countByEntryId(entryId: Int): Long =
        PantryEntryImagesTable
            .selectAll()
            .where { PantryEntryImagesTable.pantryEntryId eq entryId }
            .count()

    override suspend fun findByIdAndEntryId(
        id: Int,
        entryId: Int,
    ): PantryEntryImage? =
        PantryEntryImagesTable
            .selectAll()
            .where { (PantryEntryImagesTable.id eq id) and (PantryEntryImagesTable.pantryEntryId eq entryId) }
            .singleOrNull()
            ?.toPantryEntryImage()

    override suspend fun updateContent(
        id: Int,
        entryId: Int,
        content: PantryEntryImageContent,
    ): PantryEntryImage? {
        val updatedCount =
            PantryEntryImagesTable.update(
                where = { (PantryEntryImagesTable.id eq id) and (PantryEntryImagesTable.pantryEntryId eq entryId) },
            ) {
                it[storageKey] = content.storageKey
                it[contentType] = content.contentType
                it[sizeBytes] = content.sizeBytes
                it[width] = content.width
                it[height] = content.height
            }

        if (updatedCount == 0) return null

        return findByIdAndEntryId(id, entryId)
    }

    override suspend fun deleteByIdAndEntryId(
        id: Int,
        entryId: Int,
    ): PantryEntryImage? {
        val existing = findByIdAndEntryId(id, entryId) ?: return null

        val deletedCount =
            PantryEntryImagesTable.deleteWhere {
                (PantryEntryImagesTable.id eq id) and (PantryEntryImagesTable.pantryEntryId eq entryId)
            }

        return if (deletedCount > 0) existing else null
    }

    override suspend fun deleteAllByEntryId(entryId: Int): List<String> {
        val existing = listByEntryId(entryId)
        if (existing.isEmpty()) return emptyList()

        PantryEntryImagesTable.deleteWhere { PantryEntryImagesTable.pantryEntryId eq entryId }

        return existing.map { it.storageKey }
    }
}
