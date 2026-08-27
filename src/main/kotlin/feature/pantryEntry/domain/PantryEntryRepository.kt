package com.shelflife.feature.pantryEntry.domain

import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage

interface PantryEntryRepository {
    suspend fun create(
        userId: Int,
        productId: Int,
        fields: PantryEntryFields,
    ): PantryEntry

    suspend fun findPageByUserId(
        userId: Int,
        afterId: Int?,
        limit: Int,
    ): PantryEntryPage

    suspend fun findByIdAndUserId(
        id: Int,
        userId: Int,
    ): PantryEntry?

    suspend fun update(
        id: Int,
        userId: Int,
        fields: PantryEntryFields,
    ): PantryEntry?

    suspend fun deleteByIdAndUserId(
        id: Int,
        userId: Int,
    ): Boolean
}
