package com.shelflife.feature.pantryEntry.routing.dto.request

import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import java.math.BigDecimal
import java.time.LocalDate

fun CreatePantryEntryRequestDto.toFields() =
    PantryEntryFields(
        storageLocationId = storageLocationId,
        unitId = unitId,
        quantityAmount = BigDecimal.valueOf(quantityAmount),
        expirationDate = expirationDate?.let { LocalDate.parse(it) },
        brandOrNote = brandOrNote,
    )

fun UpdatePantryEntryRequestDto.toFields() =
    PantryEntryFields(
        storageLocationId = storageLocationId,
        unitId = unitId,
        quantityAmount = BigDecimal.valueOf(quantityAmount),
        expirationDate = expirationDate?.let { LocalDate.parse(it) },
        brandOrNote = brandOrNote,
    )
