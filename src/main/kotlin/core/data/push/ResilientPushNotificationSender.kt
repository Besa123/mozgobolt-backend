/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.push

import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.core.resilience.ResiliencePolicy
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import java.util.concurrent.TimeoutException

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
    private val resilience: ResiliencePolicy,
) : PushNotificationSender {
    override suspend fun send(
        installationId: String,
        data: Map<String, String>,
    ) = runSuspendCatching {
        resilience.execute { delegate.send(installationId, data) }
    }.onFailure { logDeliveryFailure(it) }.getOrThrow()

    private fun logDeliveryFailure(throwable: Throwable) {
        when (throwable) {
            is CallNotPermittedException -> {
                logger.warn { "Push delivery skipped — circuit open" }
            }

            is BulkheadFullException -> {
                logger.warn { "Push delivery rejected — concurrency limit reached" }
            }

            is TimeoutException -> {
                logger.warn { "Push delivery timed out" }
            }

            is InvalidPushTargetException -> {
                logger.info { "Push target installation is stale" }
            }

            is TransientPushDeliveryException -> {
                logger.warn { "Push delivery failed after retries (${throwable.javaClass.simpleName})" }
            }

            is PermanentPushDeliveryException -> {
                logger.error { "Push delivery failed permanently (${throwable.javaClass.simpleName})" }
            }

            else -> {
                logger.error { "Push delivery failed after retries (${throwable.javaClass.simpleName})" }
            }
        }
    }
}
