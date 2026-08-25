package com.shelflife.core.modules.plugin

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders

fun Application.configureForwardedHeaders() {
    install(XForwardedHeaders) {
        skipLastProxies(1)
    }
}
