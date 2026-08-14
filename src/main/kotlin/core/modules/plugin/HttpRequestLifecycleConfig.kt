package com.besa.boardShare.core.modules.plugin

import io.ktor.server.application.*
import io.ktor.server.http.*

fun Application.configureHttpRequestLifecycle() {
    install(HttpRequestLifecycle) {
        cancelCallOnClose = true
    }
}
