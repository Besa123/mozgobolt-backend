package com.shelflife.feature.product.routing

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.product.domain.model.UnitCategory

class FakeProductService : ProductService {
    data class CreateCall(
        val userId: Int,
        val name: String,
        val defaultLifespanDays: Int?,
        val defaultUnitCategory: UnitCategory?,
    )

    var searchResult: List<Product> = emptyList()
    var lastSearchQuery: String? = null
        private set

    var listOwnedByResult: List<Product> = emptyList()

    var findVisibleByIdResult: Product? = null

    var createResult: AppResult<Product, ProductError> =
        AppResult.Success(
            Product(id = 1, name = "Sonka", ownerId = 1, defaultLifespanDays = null, defaultUnitCategory = null),
        )
    var lastCreateCall: CreateCall? = null
        private set
    var createCallCount: Int = 0
        private set

    data class RenameCall(
        val userId: Int,
        val productId: Int,
        val newName: String,
    )

    var renameResult: AppResult<Product, ProductError> =
        AppResult.Success(
            Product(id = 1, name = "Sonka", ownerId = 1, defaultLifespanDays = null, defaultUnitCategory = null),
        )
    var lastRenameCall: RenameCall? = null
        private set
    var renameCallCount: Int = 0
        private set

    override suspend fun search(
        userId: Int,
        query: String,
    ): List<Product> {
        lastSearchQuery = query
        return searchResult
    }

    override suspend fun listOwnedBy(userId: Int): List<Product> = listOwnedByResult

    override suspend fun findVisibleById(
        userId: Int,
        productId: Int,
    ): Product? = findVisibleByIdResult

    override suspend fun createPrivateProduct(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
    ): AppResult<Product, ProductError> {
        createCallCount++
        lastCreateCall = CreateCall(userId, name, defaultLifespanDays, defaultUnitCategory)
        return createResult
    }

    override suspend fun renameProduct(
        userId: Int,
        productId: Int,
        newName: String,
    ): AppResult<Product, ProductError> {
        renameCallCount++
        lastRenameCall = RenameCall(userId, productId, newName)
        return renameResult
    }
}
