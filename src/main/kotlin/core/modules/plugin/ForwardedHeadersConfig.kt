package com.besa.boardShare.core.modules.plugin

import io.ktor.server.application.*
import io.ktor.server.plugins.forwardedheaders.*

fun Application.configureForwardedHeaders() {
    install(XForwardedHeaders) {
        skipLastProxies(1)
    }
}
