package com.shelflife.feature.pantryEntry.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntry.domain.PantryEntryRepository
import com.shelflife.feature.pantryEntry.domain.PantryEntryService
import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.quantityUnit.domain.QuantityUnitRepository
import com.shelflife.feature.storageLocation.domain.StorageLocationRepository
import java.math.BigDecimal

private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 200

class PantryEntryServiceI(
    private val pantryEntryRepository: PantryEntryRepository,
    private val productService: ProductService,
    private val storageLocationRepository: StorageLocationRepository,
    private val quantityUnitRepository: QuantityUnitRepository,
    private val tx: TransactionalRunner,
) : PantryEntryService {
    override suspend fun createEntry(
        userId: Int,
        product: ProductReference,
        fields: PantryEntryFields,
    ): AppResult<PantryEntry, PantryEntryError> =
        tx.transactional {
            val productId =
                when (product) {
                    is ProductReference.Existing -> {
                        if (productService.findVisibleById(userId, product.productId) == null) {
                            return@transactional AppResult.Error(PantryEntryError.PRODUCT_NOT_VISIBLE)
                        }
                        product.productId
                    }

                    is ProductReference.New -> {
                        when (
                            val created =
                                productService.createPrivateProduct(
                                    userId = userId,
                                    name = product.name,
                                    defaultLifespanDays = null,
                                    defaultUnitCategory = null,
                                )
                        ) {
                            is AppResult.Error ->
                                return@transactional AppResult.Error(PantryEntryError.PRODUCT_NAME_ALREADY_EXISTS)

                            is AppResult.Success -> created.data.id
                        }
                    }
                }

            if (fields.storageLocationId != null &&
                storageLocationRepository.findByIdAndUserId(fields.storageLocationId, userId) == null
            ) {
                return@transactional AppResult.Error(PantryEntryError.STORAGE_LOCATION_NOT_FOUND)
            }

            if (quantityUnitRepository.findById(fields.unitId) == null) {
                return@transactional AppResult.Error(PantryEntryError.UNIT_NOT_FOUND)
            }

            val entry = pantryEntryRepository.create(userId = userId, productId = productId, fields = fields)

            AppResult.Success(entry)
        }

    override suspend fun listForUser(
        userId: Int,
        afterId: Int?,
        limit: Int?,
    ): PantryEntryPage {
        val clampedLimit = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)

        return tx.transactional { pantryEntryRepository.findPageByUserId(userId, afterId, clampedLimit) }
    }

    override suspend fun updateEntry(
        userId: Int,
        entryId: Int,
        fields: PantryEntryFields,
    ): AppResult<UpdateEntryOutcome, PantryEntryError> =
        tx.transactional {
            if (pantryEntryRepository.findByIdAndUserId(entryId, userId) == null) {
                return@transactional AppResult.Error(PantryEntryError.NOT_FOUND)
            }

            if (fields.quantityAmount <= BigDecimal.ZERO) {
                pantryEntryRepository.deleteByIdAndUserId(entryId, userId)
                return@transactional AppResult.Success(UpdateEntryOutcome.Deleted)
            }

            if (fields.storageLocationId != null &&
                storageLocationRepository.findByIdAndUserId(fields.storageLocationId, userId) == null
            ) {
                return@transactional AppResult.Error(PantryEntryError.STORAGE_LOCATION_NOT_FOUND)
            }

            if (quantityUnitRepository.findById(fields.unitId) == null) {
                return@transactional AppResult.Error(PantryEntryError.UNIT_NOT_FOUND)
            }

            val updated =
                pantryEntryRepository.update(id = entryId, userId = userId, fields = fields)
                    ?: return@transactional AppResult.Error(PantryEntryError.NOT_FOUND)

            AppResult.Success(UpdateEntryOutcome.Updated(updated))
        }

    override suspend fun deleteEntry(
        userId: Int,
        entryId: Int,
    ): AppResult<Unit, PantryEntryError> =
        tx.transactional {
            if (pantryEntryRepository.deleteByIdAndUserId(entryId, userId)) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(PantryEntryError.NOT_FOUND)
            }
        }
}
