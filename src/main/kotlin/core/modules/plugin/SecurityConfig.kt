package com.besa.boardShare.core.modules.plugin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.besa.boardShare.core.domain.security.AuthConstants.CLAIM_USER_ID
import com.besa.boardShare.core.domain.security.AuthConstants.ENV_JWT_AUDIENCE
import com.besa.boardShare.core.domain.security.AuthConstants.ENV_JWT_ISSUER
import com.besa.boardShare.core.domain.security.AuthConstants.ENV_JWT_SECRET
import com.besa.boardShare.core.domain.security.AuthConstants.PROTECT_ENDPOINT_JWT
import com.besa.boardShare.core.utility.module.dotEnv.DotEnv
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    val jwtRealm = "KockaKölcsönző Zárt Rendszer"
    val jwtSecret =
        DotEnv.INSTANCE.get(ENV_JWT_SECRET) ?: error("Hiányzó $ENV_JWT_SECRET")
    val jwtIssuer =
        DotEnv.INSTANCE.get(ENV_JWT_ISSUER) ?: error("Missing $ENV_JWT_ISSUER")
    val jwtAudience =
        DotEnv.INSTANCE.get(ENV_JWT_AUDIENCE) ?: error("Missing $ENV_JWT_AUDIENCE")

    install(Authentication) {
        jwt(PROTECT_ENDPOINT_JWT) {
            realm = jwtRealm

            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )

            validate { credential ->
                val userId = credential.payload.getClaim(CLAIM_USER_ID).asInt()
                if (userId != null) {
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