package com.besa.boardShare.core.modules

import com.besa.boardShare.core.modules.plugin.*
import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.resources.*

fun Application.configureModules() {
    install(Resources)
    install(AutoHeadResponse)
    configureSecurity()
    configureCors()
    configureContentNegotiation()
    configureRateLimit()
    configureCallId()
    configureCallLogging()
    configureRequestValidation()
    configureStatusPages()
    configureHttpRequestLifecycle()
}