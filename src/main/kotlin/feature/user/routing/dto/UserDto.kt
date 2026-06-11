package com.besa.boardShare.feature.user.routing.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val password: String,
    val email: String,
    val name: String
)
