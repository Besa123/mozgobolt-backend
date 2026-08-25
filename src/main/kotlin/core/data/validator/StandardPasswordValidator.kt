package com.shelflife.core.data.validator

import com.shelflife.core.domain.validation.PasswordValidator

class StandardPasswordValidator : PasswordValidator {
    override fun isValid(password: String): Boolean {
        if (password.length < MIN_PASSWORD_LENGTH) return false
        if (password.length > MAX_PASSWORD_LENGTH) return false

        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }

        return hasUpperCase && hasLowerCase && hasDigit
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 128
    }
}
