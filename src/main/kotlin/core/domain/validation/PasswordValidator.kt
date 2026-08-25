package com.shelflife.core.domain.validation

interface PasswordValidator {
    fun isValid(password: String): Boolean
}
