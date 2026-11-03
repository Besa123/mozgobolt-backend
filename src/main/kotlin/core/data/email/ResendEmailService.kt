/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.email

import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.resend.Resend
import com.resend.core.exception.ResendException
import com.resend.core.net.RequestOptions
import com.resend.services.emails.model.CreateEmailOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

private val logger = KotlinLogging.logger {}

private val RESEND_CONNECT_TIMEOUT = Duration.ofSeconds(2)
private val RESEND_CALL_TIMEOUT = Duration.ofSeconds(4)

class ResendEmailService internal constructor(
    private val config: AppConfig,
    private val emailSender: EmailSender,
) : EmailService {
    constructor(config: AppConfig) : this(config, ResendEmailSender(config.email.resendApiKey))

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
            idempotencyKey = emailIdempotencyKey("verify-email", to, token),
        )
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        val resetUrl = "${config.baseUrl}/reset#token=$token"
        sendEmail(
            to = to,
            subject = "Reset your password",
            html = buildPasswordResetHtml(resetUrl),
            logLabel = "Password reset email",
            idempotencyKey = emailIdempotencyKey("reset-password", to, token),
        )
    }

    private suspend fun sendEmail(
        to: String,
        subject: String,
        html: String,
        logLabel: String,
        idempotencyKey: String,
    ) {
        val email =
            OutgoingEmail(
                idempotencyKey = idempotencyKey,
                fromAddress = config.email.fromAddress,
                recipient = to,
                subject = subject,
                html = html,
            )

        val messageId = emailSender.send(email)

        logger.info { "$logLabel sent [id=$messageId]" }
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

internal data class OutgoingEmail(
    val idempotencyKey: String,
    val fromAddress: String,
    val recipient: String,
    val subject: String,
    val html: String,
)

internal fun interface EmailSender {
    suspend fun send(email: OutgoingEmail): String
}

private class ResendEmailSender(
    apiKey: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EmailSender {
    private val resend =
        Resend
            .builder()
            .apiKey(apiKey)
            .connectTimeout(RESEND_CONNECT_TIMEOUT)
            .readTimeout(RESEND_CALL_TIMEOUT)
            .writeTimeout(RESEND_CALL_TIMEOUT)
            .callTimeout(RESEND_CALL_TIMEOUT)
            .build()

    override suspend fun send(email: OutgoingEmail): String =
        runSuspendCatching {
            runInterruptible(ioDispatcher) {
                val params =
                    CreateEmailOptions
                        .builder()
                        .from(email.fromAddress)
                        .to(email.recipient)
                        .subject(email.subject)
                        .html(email.html)
                        .build()

                val requestOptions =
                    RequestOptions
                        .builder()
                        .setIdempotencyKey(email.idempotencyKey)
                        .timeout(RESEND_CALL_TIMEOUT)
                        .build()
                resend.emails().send(params, requestOptions).id
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure -> throw failure.toEmailDeliveryExceptionOrNull() ?: failure },
        )
}

private fun emailIdempotencyKey(
    emailKind: String,
    recipient: String,
    token: String,
): String {
    // Resend's idempotency header must not contain the verification/reset bearer token itself.
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest("$emailKind\u0000$recipient\u0000$token".toByteArray(StandardCharsets.UTF_8))
    return "mozgobolt-${HexFormat.of().formatHex(hash)}"
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_THRESHOLD = 500

private fun Throwable.toEmailDeliveryExceptionOrNull(): EmailDeliveryException? =
    when (this) {
        is ResendException, is IOException, is IllegalArgumentException -> toEmailDeliveryException()
        else -> null
    }

internal fun Throwable.toEmailDeliveryException(): EmailDeliveryException {
    val statusCode = (this as? ResendException)?.statusCode

    return when {
        hasIOExceptionCause() -> {
            TransientEmailDeliveryException("Network failure sending email", this)
        }

        statusCode == HTTP_TOO_MANY_REQUESTS || (statusCode != null && statusCode >= HTTP_SERVER_ERROR_THRESHOLD) -> {
            TransientEmailDeliveryException("Resend API returned $statusCode", this)
        }

        statusCode != null -> {
            PermanentEmailDeliveryException("Resend API returned $statusCode", this)
        }

        this is ResendException -> {
            TransientEmailDeliveryException("Failed to send email through Resend", this)
        }

        else -> {
            PermanentEmailDeliveryException("Unexpected email delivery failure", this)
        }
    }
}

private fun Throwable.hasIOExceptionCause(): Boolean =
    generateSequence(this) { current -> current.cause }
        .any { cause -> cause is IOException }

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
