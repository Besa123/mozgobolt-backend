package com.besa.shelflife.core.domain.email

interface EmailService {
    suspend fun sendVerificationEmail(
        to: String,
        token: String,
    )
}
