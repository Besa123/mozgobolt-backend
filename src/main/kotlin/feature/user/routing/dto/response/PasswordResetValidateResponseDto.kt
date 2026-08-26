package com.shelflife.feature.user.routing.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class PasswordResetValidateResponseDto(
    val valid: Boolean,
    val email: String,
)
