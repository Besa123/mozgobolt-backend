package com.mozgobolt.core.domain.validation

import kotlin.test.Test
import kotlin.test.assertTrue

class MessengerUsernameValidationTest {
    @Test
    fun `null is accepted — a messenger username is always optional`() {
        assertTrue(validateMessengerUsername(null).isEmpty())
    }

    @Test
    fun `blank is rejected`() {
        assertTrue(validateMessengerUsername("").isNotEmpty())
        assertTrue(validateMessengerUsername("   ").isNotEmpty())
    }

    @Test
    fun `a plausible username is accepted`() {
        assertTrue(validateMessengerUsername("family.frost.bakery").isEmpty())
        assertTrue(validateMessengerUsername("driver123").isEmpty())
    }

    @Test
    fun `too short is rejected`() {
        assertTrue(validateMessengerUsername("abcd").isNotEmpty())
    }

    @Test
    fun `too long is rejected`() {
        assertTrue(validateMessengerUsername("a".repeat(MESSENGER_USERNAME_MAX_LENGTH + 1)).isNotEmpty())
    }

    @Test
    fun `a leading or trailing period is rejected`() {
        assertTrue(validateMessengerUsername(".driverName").isNotEmpty())
        assertTrue(validateMessengerUsername("driverName.").isNotEmpty())
    }

    @Test
    fun `consecutive periods are rejected`() {
        assertTrue(validateMessengerUsername("driver..name").isNotEmpty())
    }

    @Test
    fun `an invalid character is rejected`() {
        assertTrue(validateMessengerUsername("driver name!").isNotEmpty())
    }
}
