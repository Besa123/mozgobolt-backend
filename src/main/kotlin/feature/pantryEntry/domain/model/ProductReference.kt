package com.shelflife.feature.pantryEntry.domain.model

sealed interface ProductReference {
    data class Existing(
        val productId: Int,
    ) : ProductReference

    data class New(
        val name: String,
    ) : ProductReference
}
