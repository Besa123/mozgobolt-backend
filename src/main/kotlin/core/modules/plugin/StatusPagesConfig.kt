package com.besa.boardShare.core.modules.plugin

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

private val logger = KotlinLogging.logger {}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            logger.warn(cause) { "Validation failed" }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "VALIDATION_FAILED",
                    "reasons" to cause.reasons
                )
            )
        }

        exception<BadRequestException> { call, cause ->
            logger.warn(cause) { "Bad request" }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "MALFORMED_REQUEST")
            )
        }

        exception<ContentTransformationException> { call, cause ->
            logger.warn(cause) { "Invalid request body" }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "INVALID_BODY")
            )
        }

        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "INTERNAL_SERVER_ERROR")
            )
        }
    }
}
