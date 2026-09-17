package com.shelflife.feature.storageLocation.routing.dto.request

import com.shelflife.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.validateDisplayName
import kotlinx.serialization.Serializable

@Serializable
data class RenameStorageLocationRequestDto(
    val name: String,
) : ValidatedRequest {
    override fun validate() = validateDisplayName(name, DISPLAY_NAME_MAX_LENGTH)
}
