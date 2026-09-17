package com.shelflife.feature.quantityUnit.domain.model

import java.math.BigDecimal

data class QuantityUnit(
    val id: Int,
    val name: String,
    val category: UnitCategory,
    val multiplier: BigDecimal,
)
