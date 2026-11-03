/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.routing.dto.response.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.TimeoutCancellationException

private val logger = KotlinLogging.logger {}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            logger.warn { "Request validation failed" }
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "VALIDATION_FAILED",
                    message = "Request validation failed",
                    details = cause.reasons,
                ),
            )
        }

        exception<TimeoutCancellationException> { call, _ ->
            val request = call.request
            val method = request.local.method.value
            val path = request.path()
            logger.warn { "Request timed out: $method $path" }
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorResponse(
                    error = "REQUEST_TIMEOUT",
                    message = "The server took too long to process this request",
                ),
            )
        }

        exception<PayloadTooLargeException> { call, _ ->
            respondPayloadTooLarge(call)
        }

        exception<BadRequestException> { call, cause ->
            respondRequestBodyFailure(
                call = call,
                cause = cause,
                error = "MALFORMED_REQUEST",
                message = "The request could not be understood",
                logMessage = "Bad request",
            )
        }

        exception<ContentTransformationException> { call, cause ->
            respondRequestBodyFailure(
                call = call,
                cause = cause,
                error = "INVALID_BODY",
                message = "The request body is missing or malformed",
                logMessage = "Invalid request body",
            )
        }

        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "INTERNAL_SERVER_ERROR",
                    message = "An unexpected error occurred",
                ),
            )
        }
    }
}

private suspend fun respondPayloadTooLarge(call: ApplicationCall) {
    call.respond(
        HttpStatusCode.PayloadTooLarge,
        ErrorResponse(
            error = "PAYLOAD_TOO_LARGE",
            message = "Request body exceeds the allowed size",
        ),
    )
}

private suspend fun respondRequestBodyFailure(
    call: ApplicationCall,
    cause: Throwable,
    error: String,
    message: String,
    logMessage: String,
) {
    if (cause.containsPayloadTooLarge()) {
        respondPayloadTooLarge(call)
    } else {
        logger.warn { logMessage }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = error, message = message))
    }
}

private fun Throwable.containsPayloadTooLarge(): Boolean =
    generateSequence(this) { it.cause }.any { it is PayloadTooLargeException }
