package com.shelflife.feature.product.domain.model

import com.shelflife.feature.quantityUnit.domain.model.UnitCategory

data class Product(
    val id: Int,
    val name: String,
    val ownerId: Int?,
    val defaultLifespanDays: Int?,
    val defaultUnitCategory: UnitCategory?,
) {
    val isGlobal: Boolean get() = ownerId == null
}
