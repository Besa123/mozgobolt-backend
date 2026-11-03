/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.validation

import com.mozgobolt.core.domain.AppResult

/** Expected errors returned by password validation. */
enum class PasswordValidationError {
    /** The password does not meet the configured policy. */
    INVALID,

    /** The breach-check service was unavailable. */
    BREACH_CHECK_UNAVAILABLE,
}

/** Validates password policy and checks for known credential breaches. */
interface PasswordValidator {
    /** Validates [password] and reports policy or breach-check failures. */
    suspend fun validate(password: String): AppResult<Unit, PasswordValidationError>
}
