package com.mozgobolt.feature.user.domain.model

import java.time.Instant

data class User(
    val id: Int,
    val email: String,
    val name: String,
    val passwordHash: String,
    val role: UserRole,
    val phoneNumber: String? = null,
    val phoneNumberVisible: Boolean = true,
    val whatsappNumber: String? = null,
    val whatsappVisible: Boolean = true,
    val viberNumber: String? = null,
    val viberVisible: Boolean = true,
    val messengerUsername: String? = null,
    val messengerVisible: Boolean = true,
    val failedLoginAttempts: Int = 0,
    val lockedUntil: Instant? = null,
    val isEmailVerified: Boolean = false,
) {
    val isLocked: Boolean
        get() = lockedUntil != null && lockedUntil.isAfter(Instant.now())
}
