package com.besa.boardShare.feature.user.domain.model

enum class RegisterError {
    ALREADY_EXISTS,
    WEAK_PASSWORD
}

enum class LoginError {
    USER_DOES_NOT_EXIST,
    INVALID_CREDENTIALS
}