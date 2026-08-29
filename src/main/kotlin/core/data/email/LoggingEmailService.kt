package com.shelflife.core.data.email

import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.modules.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Development-only email service that logs verification links to console.
 * Used when no Resend API key is configured.
 */
class LoggingEmailService(
    private val config: AppConfig,
) : EmailService {
    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) {
        val verificationUrl = "${config.baseUrl}/api/v1/auth/verify-email?token=$token"

        logger.info {
            """
                |
                |╔══════════════════════════════════════════════════════════════╗
                |║  VERIFICATION EMAIL (dev mode — not actually sent)         ║
                |╠══════════════════════════════════════════════════════════════╣
                |║  To:    $to
                |║  Link:  $verificationUrl
                |╚══════════════════════════════════════════════════════════════╝
            """.trimMargin()
        }
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        val resetUrl = "${config.baseUrl}/reset?token=$token"

        logger.info {
            """
                |
                |╔══════════════════════════════════════════════════════════════╗
                |║  PASSWORD RESET EMAIL (dev mode — not actually sent)       ║
                |╠══════════════════════════════════════════════════════════════╣
                |║  To:    $to
                |║  Link:  $resetUrl
                |╚══════════════════════════════════════════════════════════════╝
            """.trimMargin()
        }
    }
}
