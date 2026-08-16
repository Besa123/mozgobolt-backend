package com.besa.boardShare.feature.user.routing.dto.request

import com.besa.boardShare.core.domain.validation.ValidatedRequest
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
) : ValidatedRequest {
    override fun validate() = buildList {
        if (email.isBlank()) add("Email is required")
        if (password.isBlank()) add("Password is required")
    }
}