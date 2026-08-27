package com.shelflife.core.utility.functions

import com.shelflife.core.domain.security.AuthConstants
import com.shelflife.core.domain.security.AuthConstants.PROTECT_ENDPOINT_JWT
import com.shelflife.core.modules.plugin.API_LIMIT
import com.shelflife.core.modules.plugin.AUTH_LIMIT
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route

fun ApplicationCall.currentUserIdOrNull(): Int? =
    principal<JWTPrincipal>()?.payload?.getClaim(AuthConstants.CLAIM_USER_ID)?.asInt()

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
