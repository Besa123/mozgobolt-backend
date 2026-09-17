package com.shelflife.feature.pantryEntry.routing.dto.request

import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.isValidIsoDate
import kotlinx.serialization.Serializable

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
