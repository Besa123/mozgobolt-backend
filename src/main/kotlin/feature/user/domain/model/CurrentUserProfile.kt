package com.mozgobolt.feature.user.domain.model

data class CurrentUserProfile(
    val id: Int,
    val email: String,
    val name: String,
    val role: UserRole,
    val emailVerified: Boolean,
    val contactInfo: UserContactInfo,
)
