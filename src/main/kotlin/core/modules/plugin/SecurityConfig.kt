package com.besa.boardShare.core.modules.plugin

import com.besa.boardShare.core.data.security.JwtTokenManager
import com.besa.boardShare.core.domain.security.AuthConstants.CLAIM_TOKEN_TYPE
import com.besa.boardShare.core.domain.security.AuthConstants.CLAIM_USER_ID
import com.besa.boardShare.core.domain.security.AuthConstants.PROTECT_ENDPOINT_JWT
import com.besa.boardShare.core.domain.security.AuthConstants.TOKEN_TYPE_ACCESS
import com.besa.boardShare.core.domain.security.TokenManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*

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