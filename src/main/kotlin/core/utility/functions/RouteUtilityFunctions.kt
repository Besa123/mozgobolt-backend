package com.mozgobolt.core.utility.functions

import com.mozgobolt.core.domain.security.AuthConstants
import com.mozgobolt.core.domain.security.AuthConstants.PROTECT_ENDPOINT_JWT
import com.mozgobolt.core.modules.plugin.API_LIMIT
import com.mozgobolt.core.modules.plugin.AUTH_LIMIT
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

const val DEVICE_ID_HEADER = "X-Device-Id"
private const val MAX_DEVICE_ID_LENGTH = 300

fun ApplicationCall.currentUserIdOrNull(): Int? =
    principal<JWTPrincipal>()?.payload?.getClaim(AuthConstants.CLAIM_USER_ID)?.asInt()

fun ApplicationCall.currentUserRoleOrNull(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim(AuthConstants.CLAIM_USER_ROLE)?.asString()

fun ApplicationCall.remainingJwtValidityOrNull(): Duration? {
    val expiresAt = principal<JWTPrincipal>()?.expiresAt?.toInstant() ?: return null
    return (expiresAt.toEpochMilli() - System.currentTimeMillis()).milliseconds
}

fun ApplicationCall.currentDeviceIdOrNull(): String? =
    request
        .header(DEVICE_ID_HEADER)
        ?.trim()
        ?.take(MAX_DEVICE_ID_LENGTH)
        ?.takeIf { it.isNotEmpty() }

fun Route.protectedApi(
    limitName: String = API_LIMIT,
    build: Route.() -> Unit,
) {
    authenticate(PROTECT_ENDPOINT_JWT) {
        rateLimit(RateLimitName(limitName)) {
            build()
        }
    }
}

fun Route.publicRateLimitedApi(
    limitName: String = AUTH_LIMIT,
    build: Route.() -> Unit,
) {
    rateLimit(RateLimitName(limitName)) {
        build()
    }
}
