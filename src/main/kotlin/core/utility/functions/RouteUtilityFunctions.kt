/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import com.mozgobolt.core.domain.security.AuthConstants
import com.mozgobolt.core.domain.security.AuthConstants.CLAIM_TOKEN_FAMILY_ID
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

/** HTTP header carrying the device installation identifier. */
const val DEVICE_ID_HEADER = "X-Device-Id"
private const val MAX_DEVICE_ID_LENGTH = 300

/** Returns the authenticated user's identifier from the JWT principal. */
fun ApplicationCall.currentUserIdOrNull(): Int? =
    principal<JWTPrincipal>()?.run { payload.getClaim(AuthConstants.CLAIM_USER_ID)?.asInt() }

/** Returns the authenticated refresh-token family identifier, if present. */
fun ApplicationCall.currentTokenFamilyIdOrNull(): String? =
    principal<JWTPrincipal>()?.run {
        payload.getClaim(CLAIM_TOKEN_FAMILY_ID).asString()?.takeIf(String::isNotBlank)
    }

/** Returns the authenticated user's role claim, if present. */
fun ApplicationCall.currentUserRoleOrNull(): String? =
    principal<JWTPrincipal>()?.run { payload.getClaim(AuthConstants.CLAIM_USER_ROLE)?.asString() }

/** Returns the remaining validity of the authenticated JWT, if it has an expiry. */
fun ApplicationCall.remainingJwtValidityOrNull(): Duration? =
    principal<JWTPrincipal>()?.run {
        expiresAt?.run { (toInstant().toEpochMilli() - System.currentTimeMillis()).milliseconds }
    }

/** Returns a trimmed, bounded device identifier from the request header. */
fun ApplicationCall.currentDeviceIdOrNull(): String? =
    request.header(DEVICE_ID_HEADER)?.run {
        trim().take(MAX_DEVICE_ID_LENGTH).takeIf { it.isNotEmpty() }
    }

/** Installs JWT authentication and the named API rate limit around [build]. */
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

/** Installs the named public rate limit around [build] without JWT authentication. */
fun Route.publicRateLimitedApi(
    limitName: String = AUTH_LIMIT,
    build: Route.() -> Unit,
) {
    rateLimit(RateLimitName(limitName)) {
        build()
    }
}
