package com.besa.boardShare.modules.plugin

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.seconds

const val AUTH_LIMIT = "auth-limit"
const val API_LIMIT = "api-limit"
const val UPLOAD_LIMIT = "upload-limit"

fun Application.configureRateLimit() {
    install(RateLimit) {
        global {
            rateLimiter(limit = 150, refillPeriod = 60.seconds)
        }

        register(RateLimitName(AUTH_LIMIT)) {
            rateLimiter(limit = 5, refillPeriod = 60.seconds)
        }

        register(RateLimitName(API_LIMIT)) {
            rateLimiter(limit = 60, refillPeriod = 60.seconds)

            requestKey { call ->
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()?.toString()
                userId ?: call.request.origin.remoteHost
            }
        }

        register(RateLimitName(UPLOAD_LIMIT)) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
        }
    }
}