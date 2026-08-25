package com.besa.shelflife.core.modules.plugin

import com.besa.shelflife.core.routing.dto.response.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.TimeoutCancellationException

private val logger = KotlinLogging.logger {}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            logger.warn(cause) { "Validation failed" }
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
            logger.warn { "Request timed out: ${call.request.local.method.value} ${call.request.local.uri}" }
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorResponse(
                    error = "REQUEST_TIMEOUT",
                    message = "The server took too long to process this request",
                ),
            )
        }

        exception<BadRequestException> { call, cause ->
            logger.warn(cause) { "Bad request" }
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "MALFORMED_REQUEST",
                    message = "The request could not be understood",
                ),
            )
        }

        exception<ContentTransformationException> { call, cause ->
            logger.warn(cause) { "Invalid request body" }
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "INVALID_BODY",
                    message = "The request body is missing or malformed",
                ),
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
