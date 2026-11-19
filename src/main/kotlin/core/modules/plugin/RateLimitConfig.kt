/*
 * SPDX-License-Identifier: Apache-2.0
 */

// Rate-limit identifiers are public configuration keys, not secrets.
@file:Suppress("HardcodedCredentials")

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.data.ratelimit.RedisRateLimitStore
import com.mozgobolt.core.domain.security.AuthConstants
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimitProviderConfig
import io.ktor.util.AttributeKey
import kotlin.time.Duration.Companion.seconds

const val AUTH_LIMIT = "auth-limit"
const val API_LIMIT = "api-limit"
const val UPLOAD_LIMIT = "upload-limit"
internal val RATE_LIMIT_STORE_ATTRIBUTE = AttributeKey<RedisRateLimitStore>("shared-rate-limit-store")

fun Application.configureRateLimit() {
    val redisUrl =
        environment.config
            .propertyOrNull("app.redis.url")
            ?.getString()
            .orEmpty()
    val issuer =
        environment.config
            .propertyOrNull("app.jwt.issuer")
            ?.getString()
            .orEmpty()
    val audience =
        environment.config
            .propertyOrNull("app.jwt.audience")
            ?.getString()
            .orEmpty()
    val rateLimitKeySecret =
        environment.config
            .propertyOrNull("app.security.passwordPepper")
            ?.getString()
            .orEmpty()
    val rateLimitStore =
        redisUrl
            .takeIf(String::isNotBlank)
            ?.let { RedisRateLimitStore(it, "$issuer:$audience", rateLimitKeySecret) }
    rateLimitStore?.let { store ->
        monitor.subscribe(ApplicationStopped) { store.stop() }
        store.start()
        attributes.put(RATE_LIMIT_STORE_ATTRIBUTE, store)
    }

    install(RateLimit) {
        global {
            configureRateLimiter(rateLimitStore, bucket = "global", limit = 150, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }

        register(RateLimitName(AUTH_LIMIT)) {
            configureRateLimiter(rateLimitStore, bucket = AUTH_LIMIT, limit = 5, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }

        register(RateLimitName(API_LIMIT)) {
            configureRateLimiter(rateLimitStore, bucket = API_LIMIT, limit = 60, refillPeriod = 60.seconds)

            requestKey { call ->
                val userId =
                    call.principal<JWTPrincipal>()?.run {
                        payload.getClaim(AuthConstants.CLAIM_USER_ID).asInt()?.toString()
                    }
                userId ?: call.request.origin.remoteHost
            }
        }

        register(RateLimitName(UPLOAD_LIMIT)) {
            configureRateLimiter(rateLimitStore, bucket = UPLOAD_LIMIT, limit = 10, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
}

private fun RateLimitProviderConfig.configureRateLimiter(
    store: RedisRateLimitStore?,
    bucket: String,
    limit: Int,
    refillPeriod: kotlin.time.Duration,
) {
    if (store == null) {
        rateLimiter(limit = limit, refillPeriod = refillPeriod)
    } else {
        rateLimiter { _, key ->
            store.rateLimiter(bucket = bucket, requestKey = key, limit = limit, refillPeriod = refillPeriod)
        }
    }
}
