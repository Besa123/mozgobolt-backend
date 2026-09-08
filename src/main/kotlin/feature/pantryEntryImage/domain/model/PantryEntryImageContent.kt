package com.shelflife.feature.pantryEntryImage.domain.model

data class PantryEntryImageContent(
    val storageKey: String,
    val contentType: String,
    val sizeBytes: Int,
    val width: Int,
    val height: Int,
)
