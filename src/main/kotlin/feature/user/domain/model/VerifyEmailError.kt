/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

enum class VerifyEmailError {
    INVALID_TOKEN,
    EXPIRED_TOKEN,
    ALREADY_VERIFIED,
}
