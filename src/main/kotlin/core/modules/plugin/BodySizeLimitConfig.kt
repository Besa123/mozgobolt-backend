package com.besa.shelflife.core.modules.plugin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

enum class BodyLimit(val bytes: Long) {
    TINY(1L * 1024),            // 1 KB  — login, register
    SMALL(16L * 1024),          // 16 KB — simple JSON, tokens
    MEDIUM(256L * 1024),        // 256 KB — rich JSON (descriptions, lists)
    LARGE(5L * 1024 * 1024),    // 5 MB  — image uploads
    GLOBAL(10L * 1024 * 1024),  // 10 MB — absolute safety net
}

fun Application.configureGlobalBodyLimit() {
    intercept(ApplicationCallPipeline.Plugins) {
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > BodyLimit.GLOBAL.bytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            finish()
        }
    }
}
