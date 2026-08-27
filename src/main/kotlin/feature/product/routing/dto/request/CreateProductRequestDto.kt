package com.shelflife.feature.product.routing.dto.request

import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.validateDisplayName
import com.shelflife.feature.product.domain.model.UnitCategory
import kotlinx.serialization.Serializable

private const val MAX_NAME_LENGTH = 100
private const val MIN_LIFESPAN_DAYS = 0
private const val MAX_LIFESPAN_DAYS = 3650

@Serializable
data class CreateProductRequestDto(
    val name: String,
    val defaultLifespanDays: Int? = null,
    val defaultUnitCategory: UnitCategory? = null,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            addAll(validateDisplayName(name, MAX_NAME_LENGTH))
            if (defaultLifespanDays != null && defaultLifespanDays !in MIN_LIFESPAN_DAYS..MAX_LIFESPAN_DAYS) {
                add("Default lifespan days must be between $MIN_LIFESPAN_DAYS and $MAX_LIFESPAN_DAYS")
            }
        }
}
