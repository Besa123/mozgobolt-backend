package com.besa.shelflife.core.data.validator

import com.besa.shelflife.core.domain.validation.PasswordValidator

class StandardPasswordValidator : PasswordValidator {
    override fun isValid(password: String): Boolean {
        if (password.length < 8) return false
        if (password.length > 128) return false

        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }

        return hasUpperCase && hasLowerCase && hasDigit
    }
}