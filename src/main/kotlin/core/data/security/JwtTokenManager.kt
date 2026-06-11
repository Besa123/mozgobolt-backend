package com.besa.boardShare.core.data.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.besa.boardShare.core.domain.security.AuthConstants
import com.besa.boardShare.core.domain.security.TokenManager
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class JwtTokenManager(
    private val secret: String,
    private val audience: String,
    private val issuer: String,
) : TokenManager {
    override fun generateAccessToken(userId: Int, email: String): String {
        val expirationDate = Date(System.currentTimeMillis() + 15.minutes.inWholeMilliseconds)

        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim(AuthConstants.CLAIM_USER_ID, userId)
            .withClaim(AuthConstants.CLAIM_EMAIL, email)
            .withExpiresAt(expirationDate)
            .sign(Algorithm.HMAC256(secret))
    }

    override fun generateRefreshToken(userId: Int): String {
        val expirationDate = Date(System.currentTimeMillis() + 30.days.inWholeMilliseconds)

        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim(AuthConstants.CLAIM_USER_ID, userId)
            .withExpiresAt(expirationDate)
            .sign(Algorithm.HMAC256(secret))
    }
}