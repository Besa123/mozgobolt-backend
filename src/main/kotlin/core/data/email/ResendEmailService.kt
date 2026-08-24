package com.besa.shelflife.core.data.email

import com.besa.shelflife.core.domain.email.EmailService
import com.besa.shelflife.core.modules.AppConfig
import com.resend.Resend
import com.resend.services.emails.model.CreateEmailOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

class ResendEmailService(private val config: AppConfig) : EmailService {

    private val resend = Resend(config.email.resendApiKey)

    override suspend fun sendVerificationEmail(to: String, token: String) {
        val verificationUrl = "${config.baseUrl}/api/v1/auth/verify-email?token=$token"

        val params = CreateEmailOptions.builder()
            .from(config.email.fromAddress)
            .to(to)
            .subject("Verify your email address")
            .html(buildVerificationHtml(verificationUrl))
            .build()

        withContext(Dispatchers.IO) {
            try {
                val response = resend.emails().send(params)
                logger.info { "Verification email sent to $to [id=${response.id}]" }
            } catch (e: Exception) {
                logger.error(e) { "Resend API failed for $to" }
                throw EmailDeliveryException("Failed to send verification email to $to")
            }
        }
    }

    private fun buildVerificationHtml(url: String): String = """
        <h2>Welcome!</h2>
        <p>Click the link below to verify your email:</p>
        <p><a href="$url">Verify Email</a></p>
        <p>This link expires in ${config.email.verificationTokenExpirationHours} hours.</p>
        <p>If you didn't create an account, ignore this email.</p>
    """.trimIndent()
}

class EmailDeliveryException(message: String) : RuntimeException(message)
