package com.besa.shelflife.core.modules.plugin

import io.ktor.server.application.*
import io.ktor.server.http.*

fun Application.configureHttpRequestLifecycle() {
    install(HttpRequestLifecycle) {
        cancelCallOnClose = true
    }
}
