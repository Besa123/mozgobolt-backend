package com.shelflife.feature.pantryEntry.domain.model

import com.shelflife.feature.product.domain.model.UnitCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class PantryEntry(
    val id: Int,
    val userId: Int,
    val productId: Int,
    val productName: String,
    val storageLocationId: Int?,
    val storageLocationName: String?,
    val unitId: Int,
    val unitName: String,
    val unitCategory: UnitCategory,
    val unitMultiplier: BigDecimal,
    val quantityAmount: BigDecimal,
    val expirationDate: LocalDate?,
    val brandOrNote: String?,
    val createdAt: Instant,
)
