package com.shelflife.core.domain.email

interface EmailService {
    suspend fun sendVerificationEmail(
        to: String,
        token: String,
    )

    suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    )
}
