package com.besa.boardShare.modules.plugin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.besa.boardShare.utility.module.DotEnv
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

const val PROTECT_ENDPOINT_JWT = "auth-jwt"

fun Application.configureSecurity() {
    val jwtRealm = "KockaKölcsönző Zárt Rendszer"
    val jwtSecret =
        DotEnv.INSTANCE.get("JWT_SECRET") ?: error("Hiányzó JWT_SECRET")
    val jwtIssuer =
        DotEnv.INSTANCE.get("JWT_ISSUER") ?: error("Missing JWT_ISSUER")
    val jwtAudience =
        DotEnv.INSTANCE.get("JWT_AUDIENCE") ?: error("Missing JWT_AUDIENCE")

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
                if (credential.payload.getClaim("userId").asString() != "") {
                    JWTPrincipal(credential.payload)
                } else null
            }

            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
            }
        }
    }
}