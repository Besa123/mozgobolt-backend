package com.besa.boardShare.core.domain.validation

interface ValidatedRequest {
    fun validate(): List<String>
}
