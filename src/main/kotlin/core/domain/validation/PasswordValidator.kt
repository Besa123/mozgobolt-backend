package com.besa.boardShare.core.domain.validation

interface PasswordValidator {
    fun isValid(password: String): Boolean
}