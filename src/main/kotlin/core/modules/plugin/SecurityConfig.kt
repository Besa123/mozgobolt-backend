package com.mozgobolt.core.modules.plugin

import com.auth0.jwt.JWTVerifier
import com.mozgobolt.core.domain.security.AuthConstants.CLAIM_TOKEN_TYPE
import com.mozgobolt.core.domain.security.AuthConstants.CLAIM_USER_ID
import com.mozgobolt.core.domain.security.AuthConstants.PROTECT_ENDPOINT_JWT
import com.mozgobolt.core.domain.security.AuthConstants.TOKEN_TYPE_ACCESS
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond

fun Application.configureSecurity() {
    val jwtRealm = "MozgoBolt"
    val accessTokenVerifier: JWTVerifier by dependencies

    install(Authentication) {
        jwt(PROTECT_ENDPOINT_JWT) {
            realm = jwtRealm

            verifier(accessTokenVerifier)

            validate { credential ->
                val userId = credential.payload.getClaim(CLAIM_USER_ID).asInt()
                val tokenType = credential.payload.getClaim(CLAIM_TOKEN_TYPE).asString()

                credential
                    .takeIf { userId != null && tokenType == TOKEN_TYPE_ACCESS }
                    ?.let { JWTPrincipal(it.payload) }
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(error = "UNAUTHORIZED", message = "Token is not valid or has expired"),
                )
            }
        }
    }
}
