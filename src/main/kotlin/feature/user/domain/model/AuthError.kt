package com.besa.boardShare.feature.user.domain.model

enum class RegisterError {
    ALREADY_EXISTS,
    WEAK_PASSWORD
}

enum class LoginError {
    INVALID_CREDENTIALS
}

enum class RefreshError {
    INVALID_CREDENTIALS
}