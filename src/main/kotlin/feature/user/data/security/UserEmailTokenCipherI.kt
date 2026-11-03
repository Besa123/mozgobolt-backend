/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.security

import com.mozgobolt.feature.user.domain.EmailTokenCipher
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class UserEmailTokenCipherI(
    passwordPepper: String,
) : EmailTokenCipher {
    private val key = deriveKey(passwordPepper)
    private val secureRandom = SecureRandom()

    override fun encrypt(
        token: String,
        context: String,
    ): String {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))

        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(nonce + cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8)))
    }

    override fun decrypt(
        ciphertext: String,
        context: String,
    ): String {
        val payload = Base64.getUrlDecoder().decode(ciphertext)
        require(payload.size > NONCE_BYTES + TAG_BYTES) { "Invalid encrypted email token" }

        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val encryptedToken = payload.copyOfRange(NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))

        return cipher
            .doFinal(encryptedToken)
            .toString(StandardCharsets.UTF_8)
    }

    private fun deriveKey(passwordPepper: String): ByteArray {
        require(passwordPepper.isNotBlank()) { "Password pepper must not be blank" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordPepper.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(KEY_DERIVATION_CONTEXT.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_DERIVATION_CONTEXT = "mozgo-email-outbox-token-encryption-v1"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
    }
}
