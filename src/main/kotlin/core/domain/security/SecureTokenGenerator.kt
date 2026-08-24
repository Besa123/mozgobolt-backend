package com.besa.shelflife.core.domain.security

import java.security.SecureRandom
import java.util.*

object SecureTokenGenerator {

    private val secureRandom = SecureRandom()

    fun generate(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
