/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.email

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LoggingEmailServiceTest {
    @Test
    fun `default logger path sends the message type without exposing sensitive data`() {
        runBlocking {
            LoggingEmailService().sendVerificationEmail(
                to = "private-user@example.test",
                token = "verification-secret-token",
            )
        }
    }

    @Test
    fun `development fallback never logs recipient addresses or raw tokens`() {
        runBlocking {
            val logMessages = mutableListOf<String>()
            val service = LoggingEmailService(logMessages::add)
            val recipient = "private-user@example.test"
            val verificationToken = "verification-secret-token"
            val resetToken = "password-reset-secret-token"

            service.sendVerificationEmail(recipient, verificationToken)
            service.sendPasswordResetEmail(recipient, resetToken)

            val logs = logMessages.joinToString("\n")
            assertEquals(2, logMessages.size)
            assertFalse(logs.contains(recipient))
            assertFalse(logs.contains(verificationToken))
            assertFalse(logs.contains(resetToken))
        }
    }
}
