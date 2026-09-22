package com.mozgobolt.core.data.validator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.mozgobolt.core.domain.security.PasswordPolicy.MAX_LENGTH as MAX_PASSWORD_LENGTH

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
    fun `rejects passwords missing one required character class`() {
        val missingOneClass =
            listOf(
                "abcdefg1" to "missing an uppercase letter",
                "ABCDEFG1" to "missing a lowercase letter",
                "Abcdefgh" to "missing a digit",
            )

        missingOneClass.forEach { (password, description) ->
            assertFalse(validator.isValid(password), "expected '$password' ($description) to be rejected")
        }
    }

    @Test
    fun `rejects a password at exactly the maximum length that is also missing a digit`() {
        assertFalse(validator.isValid("Ab" + "a".repeat(MAX_PASSWORD_LENGTH - 2)))
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
