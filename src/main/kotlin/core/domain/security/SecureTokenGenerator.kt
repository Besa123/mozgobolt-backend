/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.security

import java.security.SecureRandom
import java.util.Base64

/** Generates cryptographically secure URL-safe random tokens. */
object SecureTokenGenerator {
    private val secureRandom = SecureRandom()

    /** Generates a token from [byteLength] random bytes. */
    fun generate(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
