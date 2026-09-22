package com.mozgobolt.core.data.email

import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.modules.plugin.ResiliencePolicy
import com.mozgobolt.core.modules.plugin.ResilienceRegistry
import com.mozgobolt.core.modules.plugin.withEmailResilience
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CallNotPermittedException

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
    }.onFailure { logDeliveryFailure(it, to) }.getOrThrow()

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) = runSuspendCatching {
        withEmailResilience(resilience) {
            delegate.sendPasswordResetEmail(to, token)
        }
    }.onFailure { logDeliveryFailure(it, to) }.getOrThrow()

    private fun logDeliveryFailure(
        throwable: Throwable,
        to: String,
    ) {
        if (throwable is CallNotPermittedException) {
            logger.warn(throwable) { "Email delivery skipped for $to — circuit open" }
        } else {
            logger.error(throwable) { "Email delivery failed for $to after retries and circuit-breaker" }
        }
    }
}
