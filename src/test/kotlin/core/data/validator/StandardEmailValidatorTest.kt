package com.mozgobolt.core.data.validator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardEmailValidatorTest {
    private val validator = StandardEmailValidator()

    @Test
    fun `normalizes by trimming and lowercasing`() {
        assertEquals("user@example.com", validator.normalize("  User@Example.COM  "))
    }

    @Test
    fun `accepts a well-formed email on a non-disposable domain`() {
        assertTrue(validator.isValid("user@example.com"))
    }

    @Test
    fun `is case-insensitive`() {
        assertTrue(validator.isValid("User@Example.COM"))
    }

    @Test
    fun `rejects a disposable domain regardless of case`() {
        assertFalse(validator.isValid("SOMEONE@0-MAIL.COM"))
    }

    @Test
    fun `accepts plus-addressing in the local part`() {
        assertTrue(validator.isValid("user+newsletter@example.com"))
    }

    @Test
    fun `accepts a multi-level subdomain`() {
        assertTrue(validator.isValid("user@mail.example.co.uk"))
    }

    @Test
    fun `rejects an email longer than the maximum length`() {
        val localPart = "a".repeat(250)
        assertFalse(validator.isValid("$localPart@example.com"))
    }

    @Test
    fun `accepts an email at exactly the maximum length of 254 characters`() {
        // "@example.com" is 12 chars; 242 + 12 = 254, the documented MAX_EMAIL_LENGTH.
        val localPart = "a".repeat(242)
        val email = "$localPart@example.com"

        assertEquals(254, email.length)
        assertTrue(validator.isValid(email))
    }

    @Test
    fun `consecutive dots in the local part are currently accepted`() {
        // Documents current (intentionally permissive) behavior rather than a hard requirement —
        // RFC 5321 disallows consecutive dots, but EMAIL_REGEX doesn't enforce that today.
        assertTrue(validator.isValid("us..er@example.com"))
    }

    @Test
    fun `rejects malformed email shapes`() {
        val malformedEmails =
            listOf(
                "user-example.com" to "missing @ entirely",
                "user@localhost" to "no domain suffix",
                "someone@0-mail.com" to "known disposable domain",
                "user@example.123" to "purely numeric top-level domain",
                "user@example.c" to "single-character top-level domain",
                "user@@example.com" to "two adjacent at-signs",
                "us@er@example.com" to "two at-signs split by content",
                ".user@example.com" to "local part starting with a dot",
                "user.@example.com" to "local part ending with a dot",
                "" to "empty string",
                "   " to "whitespace-only string",
                "@example.com" to "missing local part",
                "user@" to "missing domain",
            )

        malformedEmails.forEach { (email, description) ->
            assertFalse(validator.isValid(email), "expected '$email' ($description) to be rejected")
        }
    }
}
