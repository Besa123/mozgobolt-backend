package com.besa.boardShare.utility.functions

import com.besa.boardShare.modules.plugin.API_LIMIT
import com.besa.boardShare.modules.plugin.PROTECT_ENDPOINT_JWT
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