/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.security

import com.mozgobolt.feature.user.domain.model.UserEmailType
import com.mozgobolt.feature.user.domain.model.userEmailOutboxEncryptionContext
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class UserEmailTokenCipherTest {
    @Test
    fun `encrypted tokens round trip without storing the raw value`() {
        val cipher = UserEmailTokenCipherI("test-password-pepper")
        val rawToken = "high-entropy-email-token"
        val context = userEmailOutboxEncryptionContext(42, UserEmailType.PASSWORD_RESET, "hashed-token")

        val encrypted = cipher.encrypt(rawToken, context)

        assertFalse(encrypted.contains(rawToken))
        assertEquals(rawToken, cipher.decrypt(encrypted, context))
        assertNotEquals(encrypted, cipher.encrypt(rawToken, context), "each encryption must use a fresh nonce")
    }

    @Test
    fun `ciphertext cannot be moved to another user or email type`() {
        val cipher = UserEmailTokenCipherI("test-password-pepper")
        val encrypted =
            cipher.encrypt(
                "high-entropy-email-token",
                userEmailOutboxEncryptionContext(42, UserEmailType.PASSWORD_RESET, "hashed-token"),
            )

        assertFailsWith<AEADBadTagException> {
            cipher.decrypt(
                encrypted,
                userEmailOutboxEncryptionContext(43, UserEmailType.PASSWORD_RESET, "hashed-token"),
            )
        }
        assertFailsWith<AEADBadTagException> {
            cipher.decrypt(
                encrypted,
                userEmailOutboxEncryptionContext(42, UserEmailType.VERIFICATION, "hashed-token"),
            )
        }
    }

    @Test
    fun `ciphertext cannot be decrypted with another application pepper`() {
        val context = userEmailOutboxEncryptionContext(42, UserEmailType.VERIFICATION, "hashed-token")
        val encrypted = UserEmailTokenCipherI("first-password-pepper").encrypt("secret-token", context)

        assertFailsWith<AEADBadTagException> {
            UserEmailTokenCipherI("second-password-pepper").decrypt(encrypted, context)
        }
    }
}
