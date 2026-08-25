package com.shelflife.core.domain.validation

interface ValidatedRequest {
    fun validate(): List<String>
}
