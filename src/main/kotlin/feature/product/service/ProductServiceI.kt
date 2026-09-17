package com.shelflife.feature.product.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.product.domain.ProductRepository
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.product.domain.model.RenameOutcome
import com.shelflife.feature.quantityUnit.domain.model.UnitCategory
import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncOperation

private const val SEARCH_RESULT_LIMIT = 20

class ProductServiceI(
    private val productRepository: ProductRepository,
    private val syncService: SyncService,
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

    override suspend fun listOwnedBy(userId: Int): List<Product> =
        tx.transactional {
            productRepository.findAllOwnedBy(userId)
        }

    override suspend fun findVisibleById(
        userId: Int,
        productId: Int,
    ): Product? = tx.transactional { productRepository.findVisibleById(userId, productId) }

    override suspend fun createPrivateProduct(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
        originDeviceId: String?,
    ): AppResult<Product, ProductError> {
        val normalizedName = name.trim()

        val created =
            tx.transactional {
                if (productRepository.findGlobalByName(normalizedName) != null) return@transactional null

                productRepository
                    .createPrivate(
                        userId = userId,
                        name = normalizedName,
                        defaultLifespanDays = defaultLifespanDays,
                        defaultUnitCategory = defaultUnitCategory,
                    )?.also {
                        syncService.recordChange(
                            userId,
                            SyncEntityType.PRODUCT,
                            it.id,
                            SyncOperation.UPSERT,
                            originDeviceId,
                        )
                    }
            } ?: return AppResult.Error(ProductError.DUPLICATE_NAME)

        return AppResult.Success(created)
    }

    override suspend fun renameProduct(
        userId: Int,
        productId: Int,
        newName: String,
        originDeviceId: String?,
    ): AppResult<Product, ProductError> {
        val normalizedName = newName.trim()

        val outcome =
            tx.transactional {
                if (!productRepository.existsOwnedBy(userId, productId)) return@transactional RenameOutcome.NotFound

                if (productRepository.findGlobalByName(normalizedName) != null) {
                    return@transactional RenameOutcome.DuplicateName
                }

                productRepository.renamePrivate(userId, productId, normalizedName).also { renamed ->
                    if (renamed is RenameOutcome.Renamed) {
                        syncService.recordChange(
                            userId,
                            SyncEntityType.PRODUCT,
                            productId,
                            SyncOperation.UPSERT,
                            originDeviceId,
                        )
                    }
                }
            }

        return when (outcome) {
            is RenameOutcome.Renamed -> AppResult.Success(outcome.product)
            is RenameOutcome.NotFound -> AppResult.Error(ProductError.NOT_FOUND)
            is RenameOutcome.DuplicateName -> AppResult.Error(ProductError.DUPLICATE_NAME)
        }
    }
}
