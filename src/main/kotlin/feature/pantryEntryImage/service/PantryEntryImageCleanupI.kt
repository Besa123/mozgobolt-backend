package com.shelflife.feature.pantryEntryImage.service

import com.shelflife.feature.pantryEntry.domain.PantryEntryImageCleanup
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageRepository

class PantryEntryImageCleanupI(
    private val pantryEntryImageRepository: PantryEntryImageRepository,
) : PantryEntryImageCleanup {
    override suspend fun deleteAllImagesForEntry(entryId: Int): List<String> =
        pantryEntryImageRepository.deleteAllByEntryId(entryId)
}
