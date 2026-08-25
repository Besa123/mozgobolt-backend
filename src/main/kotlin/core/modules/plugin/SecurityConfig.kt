package com.besa.shelflife.core.modules.plugin

import com.besa.shelflife.core.data.security.JwtTokenManager
import com.besa.shelflife.core.domain.security.AuthConstants.CLAIM_TOKEN_TYPE
import com.besa.shelflife.core.domain.security.AuthConstants.CLAIM_USER_ID
import com.besa.shelflife.core.domain.security.AuthConstants.PROTECT_ENDPOINT_JWT
import com.besa.shelflife.core.domain.security.AuthConstants.TOKEN_TYPE_ACCESS
import com.besa.shelflife.core.domain.security.TokenManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond

fun Application.configureSecurity() {
    val jwtRealm = "KockaKölcsönző Zárt Rendszer"
    val tokenManager: TokenManager by dependencies
    val jwtTokenManager = tokenManager as JwtTokenManager

    install(Authentication) {
        jwt(PROTECT_ENDPOINT_JWT) {
            realm = jwtRealm

            verifier(jwtTokenManager.accessTokenVerifier)

            validate { credential ->
                val userId = credential.payload.getClaim(CLAIM_USER_ID).asInt()
                val tokenType = credential.payload.getClaim(CLAIM_TOKEN_TYPE).asString()

                if (userId != null && tokenType == TOKEN_TYPE_ACCESS) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }

            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
            }
        }
    }
}
