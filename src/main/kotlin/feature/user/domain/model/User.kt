package com.besa.shelflife.feature.user.domain.model

import java.time.Instant

data class User(
    val id: Int,
    val email: String,
    val name: String,
    val passwordHash: String,
    val failedLoginAttempts: Int = 0,
    val lockedUntil: Instant? = null,
    val isEmailVerified: Boolean = false,
) {
    val isLocked: Boolean
        get() = lockedUntil != null && lockedUntil.isAfter(Instant.now())
}
