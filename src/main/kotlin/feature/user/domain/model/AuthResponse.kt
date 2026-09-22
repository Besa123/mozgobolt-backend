package com.mozgobolt.feature.user.domain.model

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
)
