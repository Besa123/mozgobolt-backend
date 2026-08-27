package com.shelflife.feature.quantityUnit.routing.dto.response

import com.shelflife.feature.product.domain.model.UnitCategory
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit
import kotlinx.serialization.Serializable

@Serializable
data class QuantityUnitResponseDto(
    val id: Int,
    val name: String,
    val category: UnitCategory,
    val multiplier: Double,
)

fun QuantityUnit.toResponseDto() =
    QuantityUnitResponseDto(
        id = id,
        name = name,
        category = category,
        multiplier = multiplier.toDouble(),
    )
