package com.shelflife.core.data.email

import com.resend.Resend
import com.resend.services.emails.model.CreateEmailOptions
import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.modules.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private val logger = KotlinLogging.logger {}

class ResendEmailService(
    private val config: AppConfig,
) : EmailService {
    private val resend = Resend(config.email.resendApiKey)

    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) {
        val verificationUrl = "${config.baseUrl}/api/v1/auth/verify-email?token=$token"

        val params =
            CreateEmailOptions
                .builder()
                .from(config.email.fromAddress)
                .to(to)
                .subject("Verify your email address")
                .html(buildVerificationHtml(verificationUrl))
                .build()

        withContext(Dispatchers.IO) {
            try {
                val response = resend.emails().send(params)
                logger.info { "Verification email sent to $to [id=${response.id}]" }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.error(e) { "Resend API failed for $to" }
                throw e.toEmailDeliveryException(to)
            }
        }
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        val resetUrl = "${config.baseUrl}/reset?token=$token"

        val params =
            CreateEmailOptions
                .builder()
                .from(config.email.fromAddress)
                .to(to)
                .subject("Reset your password")
                .html(buildPasswordResetHtml(resetUrl))
                .build()

        withContext(Dispatchers.IO) {
            try {
                val response = resend.emails().send(params)
                logger.info { "Password reset email sent to $to [id=${response.id}]" }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.error(e) { "Resend API failed for $to" }
                throw e.toEmailDeliveryException(to)
            }
        }
    }

    @Suppress("MultilineRawStringIndentation")
    private fun buildVerificationHtml(url: String): String =
        """
        <h2>Welcome!</h2>
        <p>Click the link below to verify your email:</p>
        <p><a href="$url">Verify Email</a></p>
        <p>This link expires in ${config.email.verificationTokenExpirationHours} hours.</p>
        <p>If you didn't create an account, ignore this email.</p>
        """.trimIndent()

    @Suppress("MultilineRawStringIndentation")
    private fun buildPasswordResetHtml(url: String): String =
        """
        <h2>Reset Your Password</h2>
        <p>Click the link below to reset your password:</p>
        <p><a href="$url">Reset Password</a></p>
        <p>This link expires in ${config.email.resetTokenExpirationMinutes} minutes.</p>
        <p>If you didn't request a password reset, you can safely ignore this email.</p>
        """.trimIndent()
}

private val HTTP_STATUS_CODE_REGEX = Regex("""Failed to send email: (\d{3})""")
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_THRESHOLD = 500

internal fun Exception.toEmailDeliveryException(to: String): EmailDeliveryException {
    val statusCode =
        HTTP_STATUS_CODE_REGEX
            .find(message.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    return when {
        cause is IOException ->
            TransientEmailDeliveryException("Network failure sending email to $to", this)

        statusCode == HTTP_TOO_MANY_REQUESTS || (statusCode != null && statusCode >= HTTP_SERVER_ERROR_THRESHOLD) ->
            TransientEmailDeliveryException("Resend API returned $statusCode for $to", this)

        statusCode != null ->
            PermanentEmailDeliveryException("Resend API returned $statusCode for $to", this)

        else ->
            TransientEmailDeliveryException("Failed to send verification email to $to", this)
    }
}

sealed class EmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class TransientEmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : EmailDeliveryException(message, cause)

class PermanentEmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : EmailDeliveryException(message, cause)
