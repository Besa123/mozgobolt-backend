package com.besa.boardShare.core.modules

import com.besa.boardShare.core.modules.plugin.configureContentNegotiation
import com.besa.boardShare.core.modules.plugin.configureRateLimit
import com.besa.boardShare.core.modules.plugin.configureSecurity
import io.ktor.server.application.*
import io.ktor.server.http.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.resources.*

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

}