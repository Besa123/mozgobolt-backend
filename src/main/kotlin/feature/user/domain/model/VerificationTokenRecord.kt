package com.besa.shelflife.feature.user.domain.model

import java.time.Instant

data class VerificationTokenRecord(
    val id: Int,
    val userId: Int,
    val token: String,
    val expiresAt: Instant,
    val used: Boolean,
)
