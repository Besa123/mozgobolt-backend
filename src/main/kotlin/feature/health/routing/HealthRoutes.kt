/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.health.routing

import com.mozgobolt.core.di.MESSAGE_RELAY_ATTRIBUTE
import com.mozgobolt.core.modules.plugin.RATE_LIMIT_STORE_ATTRIBUTE
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private const val STATUS_KEY = "status"
private const val DB_VALIDATION_TIMEOUT_SECONDS = 2
private val DB_READINESS_TIMEOUT = 3.seconds

fun Route.infrastructureRoutes(
    dataSource: DataSource,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf(STATUS_KEY to "healthy"))
    }

    get("/ready") {
        val databaseProbe =
            withTimeoutOrNull(DB_READINESS_TIMEOUT) {
                runSuspendCatching {
                    runInterruptible(ioDispatcher) {
                        dataSource.connection.use { it.isValid(DB_VALIDATION_TIMEOUT_SECONDS) }
                    }
                }
            }
        if (databaseProbe == null) logger.warn { "Readiness check database probe timed out" }
        val isDatabaseReady =
            databaseProbe
                ?.let { probe ->
                    probe
                        .onError { logger.warn(it) { "Readiness check database probe failed" } }
                        .getOrElse { false }
                }
                ?: false
        val rateLimitStore = call.application.attributes.getOrNull(RATE_LIMIT_STORE_ATTRIBUTE)
        val isSharedRateLimitStoreReady =
            if (rateLimitStore == null) {
                true
            } else {
                val result =
                    runSuspendCatching { rateLimitStore.ping() }
                        .map { true }
                        .onError { failure ->
                            logger.warn {
                                "Readiness check shared rate-limit backend failed (${failure.javaClass.simpleName})"
                            }
                        }
                result.getOrElse { false }
            }
        val isMessageRelayReady =
            call.application.attributes
                .getOrNull(MESSAGE_RELAY_ATTRIBUTE)
                ?.isReady
                ?: true
        val isApplicationReady = isDatabaseReady && isSharedRateLimitStoreReady && isMessageRelayReady

        if (isApplicationReady) {
            call.respond(HttpStatusCode.OK, mapOf(STATUS_KEY to "ready"))
        } else {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    error =
                        if (isDatabaseReady) {
                            if (isSharedRateLimitStoreReady) {
                                "MESSAGE_RELAY_UNAVAILABLE"
                            } else {
                                "RATE_LIMIT_STORE_UNAVAILABLE"
                            }
                        } else {
                            "DATABASE_UNAVAILABLE"
                        },
                ),
            )
        }
    }
}
