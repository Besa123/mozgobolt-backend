package com.shelflife.feature.user.routing.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class SignInResponseDto(
    val accessToken: String,
    val refreshToken: String,
)
