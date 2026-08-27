package com.shelflife.feature.product.data.mapper

import com.shelflife.feature.product.data.database.ProductEntity
import com.shelflife.feature.product.domain.model.Product

fun ProductEntity.toProduct() =
    Product(
        id = id.value,
        name = name,
        ownerId = user?.id?.value,
        defaultLifespanDays = defaultLifespanDays,
        defaultUnitCategory = defaultUnitCategory,
    )
