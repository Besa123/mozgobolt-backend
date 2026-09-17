package com.shelflife.feature.product.domain

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.quantityUnit.domain.model.UnitCategory

interface ProductService {
    suspend fun search(
        userId: Int,
        query: String,
    ): List<Product>

    suspend fun listOwnedBy(userId: Int): List<Product>

    suspend fun findVisibleById(
        userId: Int,
        productId: Int,
    ): Product?

    suspend fun createPrivateProduct(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
        originDeviceId: String? = null,
    ): AppResult<Product, ProductError>

    suspend fun renameProduct(
        userId: Int,
        productId: Int,
        newName: String,
        originDeviceId: String? = null,
    ): AppResult<Product, ProductError>
}
