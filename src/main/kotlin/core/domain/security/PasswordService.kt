package com.besa.shelflife.core.domain.security

interface PasswordService {
    fun hashPassword(password: String): String
    fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean
}