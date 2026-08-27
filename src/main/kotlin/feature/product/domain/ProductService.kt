package com.shelflife.feature.product.domain

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.product.domain.model.UnitCategory

interface ProductService {
    suspend fun search(
        userId: Int,
        query: String,
    ): List<Product>

    /** All of [userId]'s own private products — for a "manage my products" screen, not autocomplete. */
    suspend fun listOwnedBy(userId: Int): List<Product>

    /** A product [userId] is allowed to reference: global (owner-less) or their own private one. */
    suspend fun findVisibleById(
        userId: Int,
        productId: Int,
    ): Product?

    suspend fun createPrivateProduct(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
    ): AppResult<Product, ProductError>

    suspend fun renameProduct(
        userId: Int,
        productId: Int,
        newName: String,
    ): AppResult<Product, ProductError>
}
