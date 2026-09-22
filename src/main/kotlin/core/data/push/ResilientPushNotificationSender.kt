package com.mozgobolt.core.data.push

import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.core.modules.plugin.ResiliencePolicy
import com.mozgobolt.core.modules.plugin.ResilienceRegistry
import com.mozgobolt.core.modules.plugin.withPushResilience
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CallNotPermittedException

private val logger = KotlinLogging.logger {}

/**
 * Decorates [PushNotificationSender] with retry + circuit-breaker per ADR 0005, mirroring
 * [com.mozgobolt.core.data.email.ResilientEmailService]. Unlike email, failure here is never the
 * caller's problem — see [com.mozgobolt.feature.vehiclePing.service.VehiclePingServiceI]'s
 * fire-and-forget use of this — but this class still *rethrows* rather than swallowing, so that
 * caller can specifically catch [InvalidPushTargetException] and delete the stale installation
 * id; deciding to ignore the failure is the caller's call to make, not this decorator's.
 */
class ResilientPushNotificationSender(
    private val delegate: PushNotificationSender,
    private val resilience: ResiliencePolicy = ResilienceRegistry.push,
) : PushNotificationSender {
    override suspend fun send(
        installationId: String,
        data: Map<String, String>,
    ) = runSuspendCatching {
        withPushResilience(resilience) { delegate.send(installationId, data) }
    }.onFailure { logDeliveryFailure(it) }.getOrThrow()

    private fun logDeliveryFailure(throwable: Throwable) {
        when (throwable) {
            is CallNotPermittedException -> logger.warn(throwable) { "Push delivery skipped — circuit open" }
            is InvalidPushTargetException -> logger.info(throwable) { "Push target installation is stale" }
            else -> logger.error(throwable) { "Push delivery failed after retries and circuit-breaker" }
        }
    }
}
