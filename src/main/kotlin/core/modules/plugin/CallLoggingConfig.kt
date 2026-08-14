package com.besa.boardShare.core.modules.plugin

import com.besa.boardShare.core.domain.security.AuthConstants.CLAIM_USER_ID
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import org.slf4j.event.Level

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO

        filter { call ->
            val path = call.request.path()
            !path.startsWith("/favicon")
        }

        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val duration = call.processingTimeMillis()
            val size = call.response.headers["Content-Length"] ?: "?"
            "$status | $method $path | ${duration}ms | ${size}B"
        }

        callIdMdc("requestId")

        mdc("userId") { call ->
            call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim(CLAIM_USER_ID)
                ?.asInt()
                ?.toString()
        }

        mdc("remoteHost") { call ->
            call.request.local.remoteHost
        }

        mdc("userAgent") { call ->
            call.request.headers[HttpHeaders.UserAgent]?.take(100)
        }

        mdc("method") { call ->
            call.request.httpMethod.value
        }

        mdc("path") { call ->
            call.request.path()
        }
    }
}
