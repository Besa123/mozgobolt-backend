package com.besa.shelflife.feature.user.domain.model

enum class RegisterError {
    ALREADY_EXISTS,
    WEAK_PASSWORD,
    INVALID_EMAIL,
}

enum class LoginError {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
}

enum class RefreshError {
    INVALID_CREDENTIALS,
    TOKEN_REUSE_DETECTED,
}
