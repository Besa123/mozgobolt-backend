package com.besa.boardShare.core.utility.functions

import com.besa.boardShare.core.domain.security.AuthConstants.PROTECT_ENDPOINT_JWT
import com.besa.boardShare.core.modules.plugin.API_LIMIT
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*

fun Route.protectedApi(
    limitName: String = API_LIMIT,
    build: Route.() -> Unit
) {
    authenticate(PROTECT_ENDPOINT_JWT) {
        rateLimit(RateLimitName(limitName)) {
            build()
        }
    }
}