package com.shelflife.feature.user.routing.dto.request

import com.shelflife.core.domain.validation.ValidatedRequest
import com.shelflife.core.domain.validation.validateDisplayName
import kotlinx.serialization.Serializable

private const val MAX_NAME_LENGTH = 100

@Serializable
data class UserCreationRequestDto(
    val password: String,
    val email: String,
    val name: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (email.isBlank()) add("Email is required")
            if (password.isBlank()) add("Password is required")
            addAll(validateDisplayName(name, MAX_NAME_LENGTH))
        }
}
