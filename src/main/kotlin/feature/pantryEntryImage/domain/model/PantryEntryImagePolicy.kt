package com.shelflife.feature.pantryEntryImage.domain.model

data class PantryEntryImagePolicy(
    val maxImagesPerEntry: Int,
    val clamAvEnabled: Boolean,
)
