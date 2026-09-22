package com.mozgobolt.core.domain.security

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureTokenGeneratorTest {
    @Test
    fun `the default token decodes to 32 random bytes`() {
        val token = SecureTokenGenerator.generate()

        val decoded = Base64.getUrlDecoder().decode(token)

        assertEquals(32, decoded.size)
    }

    @Test
    fun `a custom byte length is honored`() {
        val token = SecureTokenGenerator.generate(byteLength = 16)

        assertEquals(16, Base64.getUrlDecoder().decode(token).size)
    }

    @Test
    fun `the token is URL-safe with no padding`() {
        val token = SecureTokenGenerator.generate()

        assertTrue(token.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `consecutive tokens are distinct`() {
        val tokens = (1..1_000).map { SecureTokenGenerator.generate() }.toSet()

        assertEquals(1_000, tokens.size, "expected 1000 distinct tokens, found a collision")
    }
}
