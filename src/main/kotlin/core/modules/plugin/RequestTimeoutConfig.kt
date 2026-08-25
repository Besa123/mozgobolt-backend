package com.besa.shelflife.core.modules.plugin

import com.besa.shelflife.core.routing.dto.response.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
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

fun Application.configureRequestTimeout() {
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            withTimeout(GLOBAL_SAFETY_NET) {
                proceed()
            }
        } catch (e: TimeoutCancellationException) {
            logger.error { "Request exceeded safety net: ${call.request.local.method.value} ${call.request.local.uri}" }
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
