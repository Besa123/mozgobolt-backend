package com.besa.boardShare.feature.user.domain.model

sealed interface TokenValidationResult {
    data class Valid(val familyId: String) : TokenValidationResult
    data class AlreadyRevoked(val familyId: String) : TokenValidationResult
    data object NotFound : TokenValidationResult
}
