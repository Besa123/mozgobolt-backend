package com.shelflife.feature.pantryEntry.routing

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntry.domain.PantryEntryService
import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome
import com.shelflife.feature.product.domain.model.UnitCategory
import java.math.BigDecimal
import java.time.Instant

class FakePantryEntryService : PantryEntryService {
    data class CreateCall(
        val userId: Int,
        val product: ProductReference,
        val fields: PantryEntryFields,
    )

    var pageResult: PantryEntryPage = PantryEntryPage(items = emptyList(), nextCursor = null)
    var lastListCall: Pair<Int?, Int?>? = null
        private set

    var createResult: AppResult<PantryEntry, PantryEntryError> = AppResult.Success(sampleEntry())
    var lastCreateCall: CreateCall? = null
        private set
    var createCallCount: Int = 0
        private set

    var updateResult: AppResult<UpdateEntryOutcome, PantryEntryError> =
        AppResult.Success(UpdateEntryOutcome.Updated(sampleEntry()))
    var updateCallCount: Int = 0
        private set

    var deleteResult: AppResult<Unit, PantryEntryError> = AppResult.Success(Unit)

    override suspend fun createEntry(
        userId: Int,
        product: ProductReference,
        fields: PantryEntryFields,
    ): AppResult<PantryEntry, PantryEntryError> {
        createCallCount++
        lastCreateCall = CreateCall(userId, product, fields)
        return createResult
    }

    override suspend fun listForUser(
        userId: Int,
        afterId: Int?,
        limit: Int?,
    ): PantryEntryPage {
        lastListCall = afterId to limit
        return pageResult
    }

    override suspend fun updateEntry(
        userId: Int,
        entryId: Int,
        fields: PantryEntryFields,
    ): AppResult<UpdateEntryOutcome, PantryEntryError> {
        updateCallCount++
        return updateResult
    }

    override suspend fun deleteEntry(
        userId: Int,
        entryId: Int,
    ): AppResult<Unit, PantryEntryError> = deleteResult

    companion object {
        fun sampleEntry() =
            PantryEntry(
                id = 1,
                userId = 1,
                productId = 1,
                productName = "Sonka",
                storageLocationId = null,
                storageLocationName = null,
                unitId = 1,
                unitName = "kg",
                unitCategory = UnitCategory.MASS,
                unitMultiplier = BigDecimal.ONE,
                quantityAmount = BigDecimal.ONE,
                expirationDate = null,
                brandOrNote = null,
                createdAt = Instant.EPOCH,
            )
    }
}
