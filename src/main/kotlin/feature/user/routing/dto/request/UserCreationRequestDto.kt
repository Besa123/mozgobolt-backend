package com.besa.shelflife.feature.user.routing.dto.request

import com.besa.shelflife.core.domain.validation.ValidatedRequest
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
            if (name.isBlank()) add("Name is required")
            if (name.length > MAX_NAME_LENGTH) add("Name must be $MAX_NAME_LENGTH characters or less")
            if (name.trim() != name) add("Name must not have leading/trailing spaces")
            if (name.contains(Regex("[<>\"'&;]"))) add("Name contains invalid characters")
        }
}
