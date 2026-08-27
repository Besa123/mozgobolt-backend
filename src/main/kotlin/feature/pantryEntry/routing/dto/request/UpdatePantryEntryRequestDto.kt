package com.shelflife.feature.pantryEntry.routing.dto.request

import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.isValidIsoDate
import kotlinx.serialization.Serializable

private const val MAX_NOTE_LENGTH = 255

/**
 * Full-state PATCH, not a sparse delta: the client always resends every editable field, including
 * ones it isn't intentionally changing. `storageLocationId: null` unambiguously means "no
 * location" — there is no separate "field omitted" case to distinguish (kotlinx.serialization
 * can't tell "absent" from "sent as null" for a nullable field anyway). `quantityAmount <= 0`
 * deletes the row instead of updating it — see decision 3, docs/domain/pantry.md.
 */
@Serializable
data class UpdatePantryEntryRequestDto(
    val storageLocationId: Int? = null,
    val unitId: Int,
    val quantityAmount: Double,
    val expirationDate: String? = null,
    val brandOrNote: String? = null,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (quantityAmount < 0.0) add("Quantity amount must be zero or greater")
            if (brandOrNote != null && brandOrNote.length > MAX_NOTE_LENGTH) {
                add("Note must be $MAX_NOTE_LENGTH characters or less")
            }
            if (expirationDate != null && !isValidIsoDate(expirationDate)) {
                add("Expiration date must be a valid ISO-8601 date (YYYY-MM-DD)")
            }
        }
}
