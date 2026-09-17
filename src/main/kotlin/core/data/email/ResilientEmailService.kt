package com.shelflife.core.data.email

import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.modules.plugin.ResiliencePolicy
import com.shelflife.core.modules.plugin.ResilienceRegistry
import com.shelflife.core.modules.plugin.withEmailResilience
import com.shelflife.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class ResilientEmailService(
    private val delegate: EmailService,
    private val resilience: ResiliencePolicy = ResilienceRegistry.email,
) : EmailService {
    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) = runSuspendCatching {
        withEmailResilience(resilience) {
            delegate.sendVerificationEmail(to, token)
        }
    }.onFailure { logger.error(it) { "Email delivery failed for $to after retries and circuit-breaker" } }
        .getOrThrow()

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) = runSuspendCatching {
        withEmailResilience(resilience) {
            delegate.sendPasswordResetEmail(to, token)
        }
    }.onFailure { logger.error(it) { "Email delivery failed for $to after retries and circuit-breaker" } }
        .getOrThrow()
}
