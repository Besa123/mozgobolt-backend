package com.mozgobolt.feature.company.routing.dto.request

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.core.domain.validation.validateDisplayName
import kotlinx.serialization.Serializable

@Serializable
data class CreateCompanyRequestDto(
    val name: String,
) : ValidatedRequest {
    override fun validate() = validateDisplayName(name, DISPLAY_NAME_MAX_LENGTH, fieldLabel = "Company name")
}
