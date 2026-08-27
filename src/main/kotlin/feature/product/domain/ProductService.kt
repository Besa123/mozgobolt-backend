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
