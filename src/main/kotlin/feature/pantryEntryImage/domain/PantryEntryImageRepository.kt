package com.shelflife.feature.pantryEntryImage.domain

import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageContent

interface PantryEntryImageRepository {
    suspend fun create(
        pantryEntryId: Int,
        content: PantryEntryImageContent,
    ): PantryEntryImage

    suspend fun listByEntryId(entryId: Int): List<PantryEntryImage>

    suspend fun countByEntryId(entryId: Int): Long

    suspend fun findByIdAndEntryId(
        id: Int,
        entryId: Int,
    ): PantryEntryImage?

    suspend fun updateContent(
        id: Int,
        entryId: Int,
        content: PantryEntryImageContent,
    ): PantryEntryImage?

    suspend fun deleteByIdAndEntryId(
        id: Int,
        entryId: Int,
    ): PantryEntryImage?

    suspend fun deleteAllByEntryId(entryId: Int): List<String>
}
