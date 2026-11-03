/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.email

/** Sends user-facing verification and password-reset messages. */
interface EmailService {
    /** Sends an email-verification message to [to] containing [token]. */
    suspend fun sendVerificationEmail(
        to: String,
        token: String,
    )

    /** Sends a password-reset message to [to] containing [token]. */
    suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    )
}
