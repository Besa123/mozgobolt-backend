package com.besa.boardShare.core.modules

import com.besa.boardShare.core.modules.plugin.configureContentNegotiation
import com.besa.boardShare.core.modules.plugin.configureRateLimit
import com.besa.boardShare.core.modules.plugin.configureSecurity
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.resources.*
import io.ktor.server.response.*

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
            val errorMessage = cause.cause?.message ?: "Hibás kérés"
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "MALFORMED_REQUEST",
                    "details" to errorMessage,
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
}