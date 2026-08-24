package com.besa.shelflife.feature.user.domain.model

enum class VerifyEmailError {
    INVALID_TOKEN,
    EXPIRED_TOKEN,
    ALREADY_VERIFIED,
}
