package com.besa.shelflife.core.data.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.besa.shelflife.core.domain.security.AuthConstants
import com.besa.shelflife.core.domain.security.TokenManager
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class JwtTokenManager(
    secret: String,
    private val audience: String,
    private val issuer: String,
) : TokenManager {

    private val algorithm = Algorithm.HMAC256(secret)

    private val refreshTokenVerifier: JWTVerifier by lazy {
        JWT.require(algorithm)
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim(AuthConstants.CLAIM_TOKEN_TYPE, AuthConstants.TOKEN_TYPE_REFRESH)
            .build()
    }

    val accessTokenVerifier: JWTVerifier by lazy {
        JWT.require(algorithm)
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim(AuthConstants.CLAIM_TOKEN_TYPE, AuthConstants.TOKEN_TYPE_ACCESS)
            .build()
    }

    override fun generateAccessToken(userId: Int): String {
        val expirationDate = Instant.now().plus(15, ChronoUnit.MINUTES)

        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim(AuthConstants.CLAIM_USER_ID, userId)
            .withClaim(AuthConstants.CLAIM_TOKEN_TYPE, AuthConstants.TOKEN_TYPE_ACCESS)
            .withExpiresAt(expirationDate)
            .sign(algorithm)
    }

    override fun generateRefreshToken(userId: Int): String {
        val expirationDate = Instant.now().plus(30, ChronoUnit.DAYS)

        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim(AuthConstants.CLAIM_USER_ID, userId)
            .withClaim(AuthConstants.CLAIM_TOKEN_TYPE, AuthConstants.TOKEN_TYPE_REFRESH)
            .withExpiresAt(expirationDate)
            .sign(algorithm)
    }

    override fun verifyAndGetUserIdFromRefreshToken(token: String) = try {
        val decodedJWT = refreshTokenVerifier.verify(token)
        decodedJWT.getClaim(AuthConstants.CLAIM_USER_ID).asInt()
    } catch (_: JWTVerificationException) {
        null
    }

    override fun hashTokenForStorage(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray(Charsets.UTF_8)).toHexString()
    }
}