package com.shelflife.feature.product.routing.dto.response

import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.UnitCategory
import kotlinx.serialization.Serializable

@Serializable
data class ProductResponseDto(
    val id: Int,
    val name: String,
    val isGlobal: Boolean,
    val defaultLifespanDays: Int?,
    val defaultUnitCategory: UnitCategory?,
)

fun Product.toResponseDto() =
    ProductResponseDto(
        id = id,
        name = name,
        isGlobal = isGlobal,
        defaultLifespanDays = defaultLifespanDays,
        defaultUnitCategory = defaultUnitCategory,
    )
