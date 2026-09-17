package com.shelflife.feature.pantryEntry.routing.dto.response

import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage
import com.shelflife.feature.quantityUnit.domain.model.UnitCategory
import kotlinx.serialization.Serializable

@Serializable
data class PantryEntryResponseDto(
    val id: Int,
    val productId: Int,
    val productName: String,
    val storageLocationId: Int?,
    val storageLocationName: String?,
    val unitId: Int,
    val unitName: String,
    val unitCategory: UnitCategory,
    val unitMultiplier: Double,
    val quantityAmount: Double,
    val expirationDate: String?,
    val brandOrNote: String?,
    val createdAt: String,
)

fun PantryEntry.toResponseDto() =
    PantryEntryResponseDto(
        id = id,
        productId = productId,
        productName = productName,
        storageLocationId = storageLocationId,
        storageLocationName = storageLocationName,
        unitId = unitId,
        unitName = unitName,
        unitCategory = unitCategory,
        unitMultiplier = unitMultiplier.toDouble(),
        quantityAmount = quantityAmount.toDouble(),
        expirationDate = expirationDate?.toString(),
        brandOrNote = brandOrNote,
        createdAt = createdAt.toString(),
    )

@Serializable
data class PantryEntryPageResponseDto(
    val items: List<PantryEntryResponseDto>,
    val nextCursor: Int?,
)

fun PantryEntryPage.toResponseDto() =
    PantryEntryPageResponseDto(
        items = items.map { it.toResponseDto() },
        nextCursor = nextCursor,
    )
