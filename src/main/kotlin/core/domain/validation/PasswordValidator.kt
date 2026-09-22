package com.mozgobolt.core.domain.validation

interface PasswordValidator {
    fun isValid(password: String): Boolean
}
