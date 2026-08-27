package com.shelflife.feature.pantryEntry.data.repository

import com.shelflife.feature.pantryEntry.data.mapper.toPantryEntry
import com.shelflife.feature.pantryEntry.domain.PantryEntryRepository
import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage
import com.shelflife.feature.product.data.database.PantryEntriesTable
import com.shelflife.feature.product.data.database.PantryEntryEntity
import com.shelflife.feature.product.data.database.ProductEntity
import com.shelflife.feature.product.data.database.ProductsTable
import com.shelflife.feature.product.data.database.QuantityUnitEntity
import com.shelflife.feature.product.data.database.QuantityUnitsTable
import com.shelflife.feature.product.data.database.StorageLocationEntity
import com.shelflife.feature.product.data.database.StorageLocationsTable
import com.shelflife.feature.user.data.database.UserEntity
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class PantryEntryRepositoryI : PantryEntryRepository {
    override suspend fun create(
        userId: Int,
        productId: Int,
        fields: PantryEntryFields,
    ): PantryEntry {
        val entity =
            PantryEntryEntity.new {
                this.user = UserEntity[userId]
                this.product = ProductEntity[productId]
                this.storageLocation = fields.storageLocationId?.let { StorageLocationEntity[it] }
                this.unit = QuantityUnitEntity[fields.unitId]
                this.quantityAmount = fields.quantityAmount
                this.expirationDate = fields.expirationDate
                this.brandOrNote = fields.brandOrNote
                this.createdAt = Instant.now()
            }

        return entity.toPantryEntry()
    }

    override suspend fun findPageByUserId(
        userId: Int,
        afterId: Int?,
        limit: Int,
    ): PantryEntryPage {
        val joined =
            PantryEntriesTable
                .innerJoin(ProductsTable, { PantryEntriesTable.productId }, { ProductsTable.id })
                .innerJoin(QuantityUnitsTable, { PantryEntriesTable.unitId }, { QuantityUnitsTable.id })
                .leftJoin(StorageLocationsTable, { PantryEntriesTable.storageLocationId }, { StorageLocationsTable.id })

        val condition =
            if (afterId != null) {
                (PantryEntriesTable.userId eq userId) and (PantryEntriesTable.id greater afterId)
            } else {
                PantryEntriesTable.userId eq userId
            }

        val rows =
            joined
                .selectAll()
                .where { condition }
                .orderBy(PantryEntriesTable.id to SortOrder.ASC)
                .limit(limit + 1)
                .map { it.toPantryEntry() }

        val hasNextPage = rows.size > limit
        val page = rows.take(limit)
        val nextCursor = if (hasNextPage) page.last().id else null

        return PantryEntryPage(items = page, nextCursor = nextCursor)
    }

    override suspend fun findByIdAndUserId(
        id: Int,
        userId: Int,
    ): PantryEntry? =
        PantryEntryEntity
            .find { (PantryEntriesTable.id eq id) and (PantryEntriesTable.userId eq userId) }
            .limit(1)
            .firstOrNull()
            ?.toPantryEntry()

    override suspend fun update(
        id: Int,
        userId: Int,
        fields: PantryEntryFields,
    ): PantryEntry? {
        val updatedCount =
            PantryEntriesTable.update(
                where = { (PantryEntriesTable.id eq id) and (PantryEntriesTable.userId eq userId) },
            ) {
                it[PantryEntriesTable.storageLocationId] = fields.storageLocationId
                it[PantryEntriesTable.unitId] = fields.unitId
                it[PantryEntriesTable.quantityAmount] = fields.quantityAmount
                it[PantryEntriesTable.expirationDate] = fields.expirationDate
                it[PantryEntriesTable.brandOrNote] = fields.brandOrNote
            }

        if (updatedCount == 0) return null

        return PantryEntryEntity[id].toPantryEntry()
    }

    override suspend fun deleteByIdAndUserId(
        id: Int,
        userId: Int,
    ): Boolean {
        val entity =
            PantryEntryEntity
                .find { (PantryEntriesTable.id eq id) and (PantryEntriesTable.userId eq userId) }
                .firstOrNull() ?: return false

        entity.delete()
        return true
    }

    private fun ResultRow.toPantryEntry() =
        PantryEntry(
            id = this[PantryEntriesTable.id].value,
            userId = this[PantryEntriesTable.userId].value,
            productId = this[PantryEntriesTable.productId].value,
            productName = this[ProductsTable.name],
            storageLocationId = getOrNull(StorageLocationsTable.id)?.value,
            storageLocationName = getOrNull(StorageLocationsTable.name),
            unitId = this[PantryEntriesTable.unitId].value,
            unitName = this[QuantityUnitsTable.name],
            unitCategory = this[QuantityUnitsTable.category],
            unitMultiplier = this[QuantityUnitsTable.multiplier],
            quantityAmount = this[PantryEntriesTable.quantityAmount],
            expirationDate = this[PantryEntriesTable.expirationDate],
            brandOrNote = this[PantryEntriesTable.brandOrNote],
            createdAt = this[PantryEntriesTable.createdAt],
        )
}
