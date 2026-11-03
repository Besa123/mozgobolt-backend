/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.routing.dto.response.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

enum class RequestTimeout(
    val duration: Duration,
) {
    FAST(10.seconds), // Auth, simple lookups
    STANDARD(30.seconds), // Normal CRUD
    SLOW(60.seconds), // Complex queries, reports
    UPLOAD(120.seconds), // File uploads, heavy processing
}

private val GLOBAL_SAFETY_NET = 180.seconds

private val longLivedConnectionPaths = mutableSetOf<String>()

fun exemptFromRequestTimeout(path: String) {
    longLivedConnectionPaths += path
}

fun Application.configureRequestTimeout() {
    configureRequestTimeout(GLOBAL_SAFETY_NET)
}

internal fun Application.configureRequestTimeout(globalSafetyNet: Duration) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path() in longLivedConnectionPaths) {
            proceed()
        } else {
            try {
                withTimeout(globalSafetyNet) {
                    proceed()
                }
            } catch (e: TimeoutCancellationException) {
                val request = call.request
                val method = request.local.method.value
                val path = request.path()
                logger.error {
                    "Request exceeded safety net: $method $path"
                }
                call.respond(
                    HttpStatusCode.GatewayTimeout,
                    ErrorResponse(
                        error = "REQUEST_TIMEOUT",
                        message = "Request exceeded the global safety net timeout",
                    ),
                )
            }
        }
    }
}
