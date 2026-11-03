/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.security

import com.mozgobolt.core.domain.AppResult

/** Expected failure returned when the breach-check service cannot be reached. */
enum class PasswordBreachCheckError {
    /** The password could not be checked because the service was unavailable. */
    UNAVAILABLE,
}

/** Checks whether a password appears in a known credential breach corpus. */
fun interface PasswordBreachChecker {
    /** Returns whether [password] is compromised or a service error. */
    suspend fun isCompromised(password: String): AppResult<Boolean, PasswordBreachCheckError>
}
