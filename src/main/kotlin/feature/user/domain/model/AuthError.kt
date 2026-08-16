package com.besa.boardShare.feature.user.domain.model

enum class RegisterError {
    ALREADY_EXISTS,
    WEAK_PASSWORD,
    INVALID_EMAIL,
}

enum class LoginError {
    INVALID_CREDENTIALS
}

enum class RefreshError {
    INVALID_CREDENTIALS
}