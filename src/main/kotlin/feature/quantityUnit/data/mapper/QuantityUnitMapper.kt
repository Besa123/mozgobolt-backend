package com.shelflife.feature.quantityUnit.data.mapper

import com.shelflife.feature.product.data.database.QuantityUnitEntity
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit

fun QuantityUnitEntity.toQuantityUnit() =
    QuantityUnit(
        id = id.value,
        name = name,
        category = category,
        multiplier = multiplier,
    )
