package com.shelflife.feature.product.domain

import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.RenameOutcome
import com.shelflife.feature.quantityUnit.domain.model.UnitCategory

interface ProductRepository {
    suspend fun search(
        userId: Int,
        query: String,
        limit: Int,
    ): List<Product>

    suspend fun findGlobalByName(name: String): Product?

    suspend fun findAllOwnedBy(userId: Int): List<Product>

    suspend fun findVisibleById(
        userId: Int,
        productId: Int,
    ): Product?

    suspend fun existsOwnedBy(
        userId: Int,
        productId: Int,
    ): Boolean

    suspend fun createPrivate(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
    ): Product?

    suspend fun renamePrivate(
        userId: Int,
        productId: Int,
        newName: String,
    ): RenameOutcome
}
