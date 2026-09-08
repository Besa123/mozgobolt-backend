package com.shelflife.feature.pantryEntryImage.domain.model

import java.time.Instant

data class PantryEntryImage(
    val id: Int,
    val pantryEntryId: Int,
    val storageKey: String,
    val contentType: String,
    val sizeBytes: Int,
    val width: Int,
    val height: Int,
    val createdAt: Instant,
)
