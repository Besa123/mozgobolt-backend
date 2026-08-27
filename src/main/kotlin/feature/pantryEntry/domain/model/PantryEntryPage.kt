package com.shelflife.feature.pantryEntry.domain.model

data class PantryEntryPage(
    val items: List<PantryEntry>,
    val nextCursor: Int?,
)
