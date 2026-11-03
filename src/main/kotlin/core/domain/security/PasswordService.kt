/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.security

/** Hashes passwords for storage and verifies candidate passwords against stored hashes. */
interface PasswordService {
    /** Produces a password hash suitable for persistent storage. */
    suspend fun hashPassword(password: String): String

    /** Reports whether [password] matches the stored [hash]. */
    suspend fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean
}
