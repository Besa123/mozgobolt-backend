package com.besa.boardShare.feature.user.routing.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class UserCreationRequestDto(
    val password: String,
    val email: String,
    val name: String
)