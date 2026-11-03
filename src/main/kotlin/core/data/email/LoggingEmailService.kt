/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.email

import com.mozgobolt.core.domain.email.EmailService
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Development-only placeholder that logs only the message type.
 * Used when no Resend API key is configured.
 */
class LoggingEmailService(
    private val log: (String) -> Unit = { message -> logger.info { message } },
) : EmailService {
    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) {
        log("Email not sent because Resend is not configured (type=verification)")
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        log("Email not sent because Resend is not configured (type=password-reset)")
    }
}
