package com.besa.boardShare.core.data.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.besa.boardShare.core.domain.security.AuthConstants
import com.besa.boardShare.core.domain.security.TokenManager
import java.time.Instant
import java.time.temporal.ChronoUnit

class JwtTokenManager(
    private val secret: String,
    private val audience: String,
    private val issuer: String,
) : TokenManager {
    val jwtVerifier: JWTVerifier by lazy {
        JWT.require(Algorithm.HMAC256(secret)).build()
    }

    override fun generateAccessToken(userId: Int, email: String): String {
        val expirationDate = Instant.now().plus(15, ChronoUnit.MINUTES)

        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim(AuthConstants.CLAIM_USER_ID, userId)
            .withExpiresAt(expirationDate)
            .sign(Algorithm.HMAC256(secret))
    }

    override fun generateRefreshToken(userId: Int): String {
        val expirationDate = Instant.now().plus(30, ChronoUnit.DAYS)

        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim(AuthConstants.CLAIM_USER_ID, userId)
            .withExpiresAt(expirationDate)
            .sign(Algorithm.HMAC256(secret))
    }

    override fun verifyAndGetUserIdFromRefreshToken(token: String) = try {
        val decodedJWT = jwtVerifier.verify(token)
        decodedJWT.getClaim(AuthConstants.CLAIM_USER_ID).asInt()
    } catch (_: JWTVerificationException) {
        null
    }
}