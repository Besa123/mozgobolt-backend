/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.security

/** Password length and common-password restrictions applied during registration and resets. */
object PasswordPolicy {
    /** Minimum accepted password length. */
    const val MIN_LENGTH = 15

    /** Maximum accepted password length. */
    const val MAX_LENGTH = 128

    private val blockedCommonPasswords =
        setOf(
            "password1234567",
            "password12345678",
            "password123456789",
            "qwertyuiopasdfg",
            "qwertyuiopasdfgh",
            "qwerty123456789",
            "letmein12345678",
            "welcome12345678",
            "admin1234567890",
            "iloveyou12345678",
            "123456789012345",
            "1234567890123456",
            "111111111111111",
            "000000000000000",
            "abcdefghijklmno",
            "abcdefghijklmnop",
        )

    /** Reports whether [password] meets the length limits and is not on the blocked list. */
    fun isValid(password: String): Boolean =
        password.length in MIN_LENGTH..MAX_LENGTH && password.lowercase() !in blockedCommonPasswords
}
