package com.besa.shelflife.core.data.validator

import com.besa.shelflife.core.data.validator.StandardPasswordValidator.Companion.MAX_PASSWORD_LENGTH
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardPasswordValidatorTest {
    private val validator = StandardPasswordValidator()

    @Test
    fun `accepts a password with upper, lower, digit and minimum length`() {
        assertTrue(validator.isValid("Abcdefg1"))
    }

    @Test
    fun `rejects a password shorter than the minimum length`() {
        assertFalse(validator.isValid("Ab1"))
    }

    @Test
    fun `rejects a password longer than the maximum length`() {
        assertFalse(validator.isValid("Ab1" + "a".repeat(MAX_PASSWORD_LENGTH)))
    }

    @Test
    fun `rejects a password missing an uppercase letter`() {
        assertFalse(validator.isValid("abcdefg1"))
    }

    @Test
    fun `rejects a password missing a lowercase letter`() {
        assertFalse(validator.isValid("ABCDEFG1"))
    }

    @Test
    fun `rejects a password missing a digit`() {
        assertFalse(validator.isValid("Abcdefgh"))
    }

    @Test
    fun `accepts a password exactly at the maximum length`() {
        assertTrue(validator.isValid("Ab1" + "a".repeat(MAX_PASSWORD_LENGTH - 3)))
    }

    @Test
    fun `rejects a password one character below the minimum length`() {
        assertFalse(validator.isValid("Abcdef1"))
    }

    @Test
    fun `accepts a password at exactly the minimum length`() {
        assertTrue(validator.isValid("Abcdefg1"))
    }

    @Test
    fun `rejects an empty password`() {
        assertFalse(validator.isValid(""))
    }

    @Test
    fun `rejects a password with only special characters`() {
        assertFalse(validator.isValid("!@#$%^&*"))
    }

    @Test
    fun `allows internal whitespace since no character-set restriction excludes it`() {
        assertTrue(validator.isValid("Abc def1"))
    }

    @Test
    fun `a unicode digit satisfies the digit requirement`() {
        assertTrue(validator.isValid("Abcdefg٣"))
    }
}
