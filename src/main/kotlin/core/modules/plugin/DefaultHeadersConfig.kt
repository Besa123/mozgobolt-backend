package com.besa.boardShare.core.modules.plugin

import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*

fun Application.configureDefaultHeaders() {
    install(DefaultHeaders) {
        header("Server", "")
        header("X-Powered-By", "")
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("X-XSS-Protection", "0")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload")
        header(
            "Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        )

        header("Cross-Origin-Opener-Policy", "same-origin")
        header("Cross-Origin-Embedder-Policy", "require-corp")
        header("Cross-Origin-Resource-Policy", "same-origin")
        header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=(), payment=()")

        header("Cache-Control", "no-store")
        header("X-Permitted-Cross-Domain-Policies", "none")
        header("X-DNS-Prefetch-Control", "off")
    }
}
