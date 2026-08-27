package com.shelflife.core.data.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.shelflife.core.domain.security.AuthConstants
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtTokenManagerTest {
    private val manager =
        JwtTokenManager(
            secret = "unit-test-secret",
            audience = "unit-test-audience",
            issuer = "unit-test-issuer",
        )

    @Test
    fun `an access token verifies and carries the correct user id`() {
        val token = manager.generateAccessToken(userId = 42)

        val decoded = manager.accessTokenVerifier.verify(token)

        assertEquals(42, decoded.getClaim(AuthConstants.CLAIM_USER_ID).asInt())
        assertEquals(AuthConstants.TOKEN_TYPE_ACCESS, decoded.getClaim(AuthConstants.CLAIM_TOKEN_TYPE).asString())
    }

    @Test
    fun `a refresh token verifies and resolves back to the user id`() {
        val token = manager.generateRefreshToken(userId = 7)

        assertEquals(7, manager.verifyAndGetUserIdFromRefreshToken(token))
    }

    @Test
    fun `an access token is not accepted where a refresh token is expected`() {
        val accessToken = manager.generateAccessToken(userId = 1)

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(accessToken))
    }

    @Test
    fun `a refresh token does not verify against the access token verifier`() {
        val refreshToken = manager.generateRefreshToken(userId = 1)

        assertFailsToVerifyAsAccessToken(refreshToken)
    }

    @Test
    fun `a malformed or tampered token is rejected`() {
        val token = manager.generateRefreshToken(userId = 1)
        val index = token.length - 2
        val tampered = token.take(index) + (if (token[index] == 'A') 'B' else 'A') + token.substring(index + 1)

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(tampered))
    }

    @Test
    fun `a token is rejected once signed with a different secret`() {
        val other =
            JwtTokenManager(
                secret = "a-different-secret",
                audience = "unit-test-audience",
                issuer = "unit-test-issuer",
            )
        val token = other.generateRefreshToken(userId = 1)

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(token))
    }

    @Test
    fun `hashing a token for storage is deterministic and collision-resistant between distinct tokens`() {
        val tokenA = manager.generateRefreshToken(userId = 1)
        val tokenB = manager.generateRefreshToken(userId = 1)

        assertEquals(manager.hashTokenForStorage(tokenA), manager.hashTokenForStorage(tokenA))
        assertNotEquals(manager.hashTokenForStorage(tokenA), manager.hashTokenForStorage(tokenB))
    }

    @Test
    fun `an expired access token is rejected`() {
        val expired =
            craftToken(
                type = AuthConstants.TOKEN_TYPE_ACCESS,
                audience = "unit-test-audience",
                issuer = "unit-test-issuer",
                expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES),
            )

        val result = runCatching { manager.accessTokenVerifier.verify(expired) }

        assertTrue(result.isFailure)
    }

    @Test
    fun `an expired refresh token is rejected`() {
        val expired =
            craftToken(
                type = AuthConstants.TOKEN_TYPE_REFRESH,
                audience = "unit-test-audience",
                issuer = "unit-test-issuer",
                expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES),
            )

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(expired))
    }

    @Test
    fun `a token signed for a different audience is rejected`() {
        val wrongAudience =
            craftToken(
                type = AuthConstants.TOKEN_TYPE_REFRESH,
                audience = "someone-elses-audience",
                issuer = "unit-test-issuer",
            )

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(wrongAudience))
    }

    @Test
    fun `a token signed by a different issuer is rejected`() {
        val wrongIssuer =
            craftToken(
                type = AuthConstants.TOKEN_TYPE_REFRESH,
                audience = "unit-test-audience",
                issuer = "someone-else",
            )

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(wrongIssuer))
    }

    @Test
    fun `a token with no token-type claim at all is rejected`() {
        val noType =
            JWT
                .create()
                .withAudience("unit-test-audience")
                .withIssuer("unit-test-issuer")
                .withClaim(AuthConstants.CLAIM_USER_ID, 1)
                .withExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .sign(Algorithm.HMAC256("unit-test-secret"))

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(noType))
    }

    @Test
    fun `tampering with the payload segment is rejected, not just the signature`() {
        val token = manager.generateRefreshToken(userId = 1)
        val parts = token.split(".")
        check(parts.size == 3) { "expected a header.payload.signature JWT" }
        val tamperedPayload = parts[1].dropLast(1) + if (parts[1].last() == 'A') 'B' else 'A'
        val tampered = listOf(parts[0], tamperedPayload, parts[2]).joinToString(".")

        assertNull(manager.verifyAndGetUserIdFromRefreshToken(tampered))
    }

    private fun craftToken(
        type: String,
        audience: String,
        issuer: String,
        expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS),
        userId: Int = 1,
        secret: String = "unit-test-secret",
    ): String =
        JWT
            .create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim(AuthConstants.CLAIM_USER_ID, userId)
            .withClaim(AuthConstants.CLAIM_TOKEN_TYPE, type)
            .withExpiresAt(expiresAt)
            .sign(Algorithm.HMAC256(secret))

    private fun assertFailsToVerifyAsAccessToken(token: String) {
        val result = runCatching { manager.accessTokenVerifier.verify(token) }
        assertTrue(result.isFailure)
    }
}
