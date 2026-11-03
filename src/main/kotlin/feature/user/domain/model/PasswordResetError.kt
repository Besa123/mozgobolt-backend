/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

enum class PasswordResetError {
    INVALID_TOKEN,
    EXPIRED_TOKEN,
    WEAK_PASSWORD,
    PASSWORD_CHECK_UNAVAILABLE,
    SAME_AS_OLD,
    USER_NOT_FOUND,
    ACCOUNT_LOCKED,
}
