package com.shelflife.feature.pantryEntry.domain

fun interface PantryEntryImageCleanup {
    suspend fun deleteAllImagesForEntry(entryId: Int): List<String>
}
