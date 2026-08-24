package com.besa.shelflife.core.domain.validation

interface ValidatedRequest {
    fun validate(): List<String>
}
