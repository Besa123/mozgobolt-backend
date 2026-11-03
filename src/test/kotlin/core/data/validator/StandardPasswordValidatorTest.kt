/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.validator

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordBreachCheckError
import com.mozgobolt.core.domain.security.PasswordBreachChecker
import com.mozgobolt.core.domain.validation.PasswordValidationError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.mozgobolt.core.domain.security.PasswordPolicy.MAX_LENGTH as MAX_PASSWORD_LENGTH
import com.mozgobolt.core.domain.security.PasswordPolicy.MIN_LENGTH as MIN_PASSWORD_LENGTH

class StandardPasswordValidatorTest {
    private val validator =
        StandardPasswordValidator(
            PasswordBreachChecker { AppResult.Success(false) },
        )

    private fun isValid(password: String): Boolean =
        runBlocking {
            validator.validate(password) is AppResult.Success
        }

    @Test
    fun `accepts a passphrase at the minimum length without composition rules`() {
        assertTrue(isValid("a".repeat(MIN_PASSWORD_LENGTH)))
    }

    @Test
    fun `rejects a password shorter than the minimum length`() {
        assertFalse(isValid("a".repeat(MIN_PASSWORD_LENGTH - 1)))
    }

    @Test
    fun `rejects a password longer than the maximum length`() {
        assertFalse(isValid("a".repeat(MAX_PASSWORD_LENGTH + 1)))
    }

    @Test
    fun `accepts a passphrase at exactly the maximum length`() {
        assertTrue(isValid("a".repeat(MAX_PASSWORD_LENGTH)))
    }

    @Test
    fun `rejects a password one character below the minimum length`() {
        assertFalse(isValid("a".repeat(MIN_PASSWORD_LENGTH - 1)))
    }

    @Test
    fun `accepts a password at exactly the minimum length`() {
        assertTrue(isValid("a".repeat(MIN_PASSWORD_LENGTH)))
    }

    @Test
    fun `rejects an empty password`() {
        assertFalse(isValid(""))
    }

    @Test
    fun `allows a password made only of special characters`() {
        assertTrue(isValid("!@#$%^&*".repeat(2)))
    }

    @Test
    fun `allows internal whitespace since no character-set restriction excludes it`() {
        assertTrue(isValid("a phrase with spaces"))
    }

    @Test
    fun `allows a unicode passphrase without character class requirements`() {
        assertTrue(isValid("abcdefghijklmn٣"))
    }

    @Test
    fun `rejects a common password regardless of letter casing`() {
        assertFalse(isValid("PASSWORD1234567"))
    }

    @Test
    fun `fails closed when the breached-password check is unavailable`() {
        runBlocking {
            val validator =
                StandardPasswordValidator {
                    AppResult.Error(PasswordBreachCheckError.UNAVAILABLE)
                }

            assertEquals(
                AppResult.Error(PasswordValidationError.BREACH_CHECK_UNAVAILABLE),
                validator.validate("a long passphrase"),
            )
        }
    }

    @Test
    fun `reports compromised passphrases as invalid`() {
        runBlocking {
            val compromisedChecker = PasswordBreachChecker { AppResult.Success(true) }
            val breachAwareValidator = StandardPasswordValidator(compromisedChecker)

            assertEquals(
                AppResult.Error(PasswordValidationError.INVALID),
                breachAwareValidator.validate("A long compromised passphrase"),
            )
        }
    }
}
