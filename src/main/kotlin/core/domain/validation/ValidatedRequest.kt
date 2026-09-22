package com.mozgobolt.core.domain.validation

interface ValidatedRequest {
    fun validate(): List<String>
}
