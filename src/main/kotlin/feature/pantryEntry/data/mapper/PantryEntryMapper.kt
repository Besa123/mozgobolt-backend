package com.shelflife.feature.pantryEntry.data.mapper

import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.product.data.database.PantryEntryEntity

fun PantryEntryEntity.toPantryEntry() =
    PantryEntry(
        id = id.value,
        userId = user.id.value,
        productId = product.id.value,
        productName = product.name,
        storageLocationId = storageLocation?.id?.value,
        storageLocationName = storageLocation?.name,
        unitId = unit.id.value,
        unitName = unit.name,
        unitCategory = unit.category,
        unitMultiplier = unit.multiplier,
        quantityAmount = quantityAmount,
        expirationDate = expirationDate,
        brandOrNote = brandOrNote,
        createdAt = createdAt,
    )
