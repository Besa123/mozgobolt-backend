package com.besa.boardShare.core.modules.plugin

import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.*

fun Application.configureRequestValidation() {
    install(RequestValidation) {
        // Add validators here as you create DTOs
    }
}
