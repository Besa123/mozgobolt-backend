package com.besa.boardShare.feature.user.routing.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class LogoutRequestDto(
    val refreshToken: String
)