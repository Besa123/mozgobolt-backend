package com.shelflife.core.data.validator

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
    fun `rejects an email without an @`() {
        assertFalse(validator.isValid("user-example.com"))
    }

    @Test
    fun `rejects an email without a domain suffix`() {
        assertFalse(validator.isValid("user@localhost"))
    }

    @Test
    fun `rejects an email on a known disposable domain`() {
        assertFalse(validator.isValid("someone@0-mail.com"))
    }

    @Test
    fun `rejects an email longer than the maximum length`() {
        val localPart = "a".repeat(250)
        assertFalse(validator.isValid("$localPart@example.com"))
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
    fun `rejects a purely numeric top-level domain`() {
        assertFalse(validator.isValid("user@example.123"))
    }

    @Test
    fun `rejects a single-character top-level domain`() {
        assertFalse(validator.isValid("user@example.c"))
    }

    @Test
    fun `rejects an email with two at-signs`() {
        assertFalse(validator.isValid("user@@example.com"))
        assertFalse(validator.isValid("us@er@example.com"))
    }

    @Test
    fun `rejects a local part starting with a dot`() {
        assertFalse(validator.isValid(".user@example.com"))
    }

    @Test
    fun `rejects a local part ending with a dot`() {
        assertFalse(validator.isValid("user.@example.com"))
    }

    @Test
    fun `rejects an empty string`() {
        assertFalse(validator.isValid(""))
    }

    @Test
    fun `rejects an email missing a local part`() {
        assertFalse(validator.isValid("@example.com"))
    }

    @Test
    fun `rejects an email missing a domain`() {
        assertFalse(validator.isValid("user@"))
    }

    @Test
    fun `consecutive dots in the local part are currently accepted`() {
        assertTrue(validator.isValid("us..er@example.com"))
    }
}
