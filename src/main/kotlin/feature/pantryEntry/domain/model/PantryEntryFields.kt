package com.shelflife.feature.pantryEntry.domain.model

import java.math.BigDecimal
import java.time.LocalDate

data class PantryEntryFields(
    val storageLocationId: Int?,
    val unitId: Int,
    val quantityAmount: BigDecimal,
    val expirationDate: LocalDate?,
    val brandOrNote: String?,
)
