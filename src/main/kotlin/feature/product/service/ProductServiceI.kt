package com.shelflife.feature.product.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.product.domain.ProductRepository
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.product.domain.model.RenameOutcome
import com.shelflife.feature.product.domain.model.UnitCategory

private const val SEARCH_RESULT_LIMIT = 20

class ProductServiceI(
    private val productRepository: ProductRepository,
    private val tx: TransactionalRunner,
) : ProductService {
    override suspend fun search(
        userId: Int,
        query: String,
    ): List<Product> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        return tx.transactional {
            productRepository.search(userId = userId, query = trimmed, limit = SEARCH_RESULT_LIMIT)
        }
    }

    override suspend fun createPrivateProduct(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
    ): AppResult<Product, ProductError> {
        val normalizedName = name.trim()

        val created =
            tx.transactional {
                productRepository.createPrivate(
                    userId = userId,
                    name = normalizedName,
                    defaultLifespanDays = defaultLifespanDays,
                    defaultUnitCategory = defaultUnitCategory,
                )
            } ?: return AppResult.Error(ProductError.DUPLICATE_NAME)

        return AppResult.Success(created)
    }

    override suspend fun renameProduct(
        userId: Int,
        productId: Int,
        newName: String,
    ): AppResult<Product, ProductError> {
        val normalizedName = newName.trim()

        return when (
            val outcome =
                tx.transactional {
                    productRepository.renamePrivate(
                        userId,
                        productId,
                        normalizedName,
                    )
                }
        ) {
            is RenameOutcome.Renamed -> AppResult.Success(outcome.product)
            is RenameOutcome.NotFound -> AppResult.Error(ProductError.NOT_FOUND)
            is RenameOutcome.DuplicateName -> AppResult.Error(ProductError.DUPLICATE_NAME)
        }
    }
}
