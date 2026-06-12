package com.besa.boardShare.feature.user.routing.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)