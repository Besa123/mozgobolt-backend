/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain

/** Encrypts email tokens before they are stored in the durable outbox. */
interface EmailTokenCipher {
    /** Encrypts [token] using [context] as authenticated associated data. */
    fun encrypt(
        token: String,
        context: String,
    ): String

    /** Decrypts [ciphertext] using the same [context] supplied during encryption. */
    fun decrypt(
        ciphertext: String,
        context: String,
    ): String
}
