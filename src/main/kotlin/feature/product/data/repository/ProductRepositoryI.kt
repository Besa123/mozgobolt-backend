package com.shelflife.feature.product.data.repository

import com.shelflife.feature.product.data.database.ProductEntity
import com.shelflife.feature.product.data.database.ProductsTable
import com.shelflife.feature.product.data.mapper.toProduct
import com.shelflife.feature.product.domain.ProductRepository
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.RenameOutcome
import com.shelflife.feature.product.domain.model.UnitCategory
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update

private const val POSTGRES_UNIQUE_VIOLATION_SQL_STATE = "23505"

class ProductRepositoryI : ProductRepository {
    override suspend fun search(
        userId: Int,
        query: String,
        limit: Int,
    ): List<Product> =
        ProductEntity
            .find {
                (ProductsTable.userId.isNull() or (ProductsTable.userId eq userId)) and
                    (ProductsTable.name.lowerCase() like containsPattern(query))
            }.orderBy(ProductsTable.name to SortOrder.ASC)
            .limit(limit)
            .map { it.toProduct() }

    override suspend fun createPrivate(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
    ): Product? {
        val inserted =
            ProductsTable
                .insertIgnore {
                    it[ProductsTable.userId] = userId
                    it[ProductsTable.name] = name
                    it[ProductsTable.defaultLifespanDays] = defaultLifespanDays
                    it[ProductsTable.defaultUnitCategory] = defaultUnitCategory
                }.insertedCount > 0

        if (!inserted) return null

        return ProductEntity
            .find { (ProductsTable.userId eq userId) and (ProductsTable.name.lowerCase() eq name.lowercase()) }
            .limit(1)
            .firstOrNull()
            ?.toProduct()
    }

    override suspend fun renamePrivate(
        userId: Int,
        productId: Int,
        newName: String,
    ): RenameOutcome {
        val updatedCount =
            try {
                ProductsTable.update(
                    where = { (ProductsTable.id eq productId) and (ProductsTable.userId eq userId) },
                ) {
                    it[name] = newName
                }
            } catch (e: ExposedSQLException) {
                if (e.sqlState == POSTGRES_UNIQUE_VIOLATION_SQL_STATE) return RenameOutcome.DuplicateName
                throw e
            }

        if (updatedCount == 0) return RenameOutcome.NotFound

        return RenameOutcome.Renamed(ProductEntity[productId].toProduct())
    }

    private companion object {
        fun containsPattern(query: String): LikePattern {
            val escaped = LikePattern.ofLiteral(query.trim().lowercase())
            return LikePattern(pattern = "%${escaped.pattern}%", escapeChar = escaped.escapeChar)
        }
    }
}
