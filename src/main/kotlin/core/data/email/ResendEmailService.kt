package com.mozgobolt.core.data.email

import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.resend.Resend
import com.resend.core.exception.ResendException
import com.resend.services.emails.model.CreateEmailOptions
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
        sendEmail(
            to = to,
            subject = "Verify your email address",
            html = buildVerificationHtml(verificationUrl),
            logLabel = "Verification email",
        )
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        val resetUrl = "${config.baseUrl}/reset?token=$token"
        sendEmail(
            to = to,
            subject = "Reset your password",
            html = buildPasswordResetHtml(resetUrl),
            logLabel = "Password reset email",
        )
    }

    private suspend fun sendEmail(
        to: String,
        subject: String,
        html: String,
        logLabel: String,
    ) {
        val params =
            CreateEmailOptions
                .builder()
                .from(config.email.fromAddress)
                .to(to)
                .subject(subject)
                .html(html)
                .build()

        withContext(Dispatchers.IO) {
            runSuspendCatching { resend.emails().send(params) }
                .onSuccess { logger.info { "$logLabel sent to $to [id=${it.id}]" } }
                .onFailure { logger.error(it) { "Resend API failed for $to" } }
                .getOrElse { throw it.toEmailDeliveryException(to) }
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

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_THRESHOLD = 500

internal fun Throwable.toEmailDeliveryException(to: String): EmailDeliveryException {
    val statusCode = (this as? ResendException)?.statusCode

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
