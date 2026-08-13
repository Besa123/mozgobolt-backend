package com.besa.boardShare.core.modules

import com.besa.boardShare.core.modules.plugin.configureContentNegotiation
import com.besa.boardShare.core.modules.plugin.configureRateLimit
import com.besa.boardShare.core.modules.plugin.configureSecurity
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.resources.*
import io.ktor.server.response.*

private val logger = KotlinLogging.logger {}

fun Application.configureModules() {
    install(Resources)
    install(AutoHeadResponse)
    install(RequestValidation)
    configureSecurity()
    configureContentNegotiation()
    configureRateLimit()

    install(HttpRequestLifecycle) {
        cancelCallOnClose = true
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            logger.warn(cause) { "Bad request" }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "MALFORMED_REQUEST")
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