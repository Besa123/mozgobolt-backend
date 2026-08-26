package com.shelflife.feature.user.domain.model

import java.time.Instant

data class PasswordResetToken(
    val id: Int,
    val userId: Int,
    val token: String,
    val expiresAt: Instant,
    val used: Boolean,
    val usedAt: Instant?,
    val createdAt: Instant,
)
