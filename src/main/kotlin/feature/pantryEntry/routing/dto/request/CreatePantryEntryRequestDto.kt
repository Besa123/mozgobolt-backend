package com.shelflife.feature.pantryEntry.routing.dto.request

import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.isValidIsoDate
import com.shelflife.core.domain.validation.validateDisplayName
import kotlinx.serialization.Serializable

private const val MAX_NOTE_LENGTH = 255
private const val MAX_NAME_LENGTH = 100

@Serializable
data class CreatePantryEntryRequestDto(
    val productId: Int? = null,
    val newProductName: String? = null,
    val storageLocationId: Int? = null,
    val unitId: Int,
    val quantityAmount: Double,
    val expirationDate: String? = null,
    val brandOrNote: String? = null,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            val hasExisting = productId != null
            val hasNew = !newProductName.isNullOrBlank()

            if (hasExisting == hasNew) {
                add("Exactly one of productId or newProductName must be provided")
            }
            if (hasNew) {
                addAll(validateDisplayName(newProductName.orEmpty(), MAX_NAME_LENGTH, fieldLabel = "New product name"))
            }
            if (quantityAmount <= 0.0) add("Quantity amount must be greater than zero")
            if (brandOrNote != null && brandOrNote.length > MAX_NOTE_LENGTH) {
                add("Note must be $MAX_NOTE_LENGTH characters or less")
            }
            if (expirationDate != null && !isValidIsoDate(expirationDate)) {
                add("Expiration date must be a valid ISO-8601 date (YYYY-MM-DD)")
            }
        }
}
