/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

enum class RegisterError {
    ALREADY_EXISTS,
    WEAK_PASSWORD,
    PASSWORD_CHECK_UNAVAILABLE,
    INVALID_EMAIL,
    INVALID_NAME,
    INVALID_PHONE_NUMBER,
    INVALID_MESSENGER_USERNAME,
}

enum class ContactInfoError {
    USER_NOT_FOUND,
    INVALID_PHONE_NUMBER,
    INVALID_WHATSAPP_NUMBER,
    INVALID_VIBER_NUMBER,
    INVALID_MESSENGER_USERNAME,
}

enum class LoginError {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
}

enum class RefreshError {
    INVALID_CREDENTIALS,
    TOKEN_REUSE_DETECTED,
}
