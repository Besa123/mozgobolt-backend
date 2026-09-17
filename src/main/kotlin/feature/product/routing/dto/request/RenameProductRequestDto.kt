package com.shelflife.feature.product.routing.dto.request

import com.shelflife.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.validateDisplayName
import kotlinx.serialization.Serializable

@Serializable
data class RenameProductRequestDto(
    val name: String,
) : ValidatedRequest {
    override fun validate() = validateDisplayName(name, DISPLAY_NAME_MAX_LENGTH)
}
