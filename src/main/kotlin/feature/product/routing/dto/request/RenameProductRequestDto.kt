package com.shelflife.feature.product.routing.dto.request

import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.validateDisplayName
import kotlinx.serialization.Serializable

private const val MAX_NAME_LENGTH = 100

@Serializable
data class RenameProductRequestDto(
    val name: String,
) : ValidatedRequest {
    override fun validate() = validateDisplayName(name, MAX_NAME_LENGTH)
}
