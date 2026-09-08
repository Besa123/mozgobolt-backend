package com.shelflife.core.data.email

import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.modules.plugin.ResiliencePolicy
import com.shelflife.core.modules.plugin.ResilienceRegistry
import com.shelflife.core.modules.plugin.withEmailResilience
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class ResilientEmailService(
    private val delegate: EmailService,
    private val resilience: ResiliencePolicy = ResilienceRegistry.email,
) : EmailService {
    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) {
        try {
            withEmailResilience(resilience) {
                delegate.sendVerificationEmail(to, token)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(e) { "Email delivery failed for $to after retries and circuit-breaker" }
            throw e
        }
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        try {
            withEmailResilience(resilience) {
                delegate.sendPasswordResetEmail(to, token)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(e) { "Email delivery failed for $to after retries and circuit-breaker" }
            throw e
        }
    }
}
