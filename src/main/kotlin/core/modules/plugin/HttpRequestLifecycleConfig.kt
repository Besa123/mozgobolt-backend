package com.shelflife.core.modules.plugin

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle

fun Application.configureHttpRequestLifecycle() {
    install(HttpRequestLifecycle) {
        cancelCallOnClose = true
    }
}
