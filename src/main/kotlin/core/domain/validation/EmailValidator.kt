package com.besa.shelflife.core.domain.validation

interface EmailValidator {
    fun normalize(email: String): String
    fun isValid(email: String): Boolean
}
