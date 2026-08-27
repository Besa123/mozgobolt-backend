package com.shelflife.feature.pantryEntry.domain

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome

interface PantryEntryService {
    suspend fun createEntry(
        userId: Int,
        product: ProductReference,
        fields: PantryEntryFields,
    ): AppResult<PantryEntry, PantryEntryError>

    suspend fun listForUser(
        userId: Int,
        afterId: Int?,
        limit: Int?,
    ): PantryEntryPage

    suspend fun updateEntry(
        userId: Int,
        entryId: Int,
        fields: PantryEntryFields,
    ): AppResult<UpdateEntryOutcome, PantryEntryError>

    suspend fun deleteEntry(
        userId: Int,
        entryId: Int,
    ): AppResult<Unit, PantryEntryError>
}
