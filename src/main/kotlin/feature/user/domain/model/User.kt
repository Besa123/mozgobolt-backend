package com.besa.boardShare.feature.user.domain.model

data class User(
    val id: Int,
    val email: String,
    val name: String,
    val passwordHash: String,
)
