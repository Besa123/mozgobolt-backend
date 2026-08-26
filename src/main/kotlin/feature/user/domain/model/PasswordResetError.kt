package com.shelflife.feature.user.domain.model

enum class PasswordResetError {
    INVALID_TOKEN,
    EXPIRED_TOKEN,
    WEAK_PASSWORD,
    SAME_AS_OLD,
    USER_NOT_FOUND,
    ACCOUNT_LOCKED,
}
