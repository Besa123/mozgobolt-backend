package com.shelflife.feature.user.routing

import com.shelflife.core.TEST_JWT_AUDIENCE
import com.shelflife.core.TEST_JWT_ISSUER
import com.shelflife.core.TEST_JWT_SECRET
import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.data.security.JwtTokenManager
import com.shelflife.core.testAccessTokenFor
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthRoutesSecurityTest {
    @Test
    fun `a valid access token is accepted`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }

            val response = client.post(AuthPaths.LOGOUT_ALL) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `no Authorization header at all is rejected`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }

            val response = client.post(AuthPaths.LOGOUT_ALL)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a refresh token presented as an access token is rejected`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }
            val manager =
                JwtTokenManager(secret = TEST_JWT_SECRET, audience = TEST_JWT_AUDIENCE, issuer = TEST_JWT_ISSUER)
            val refreshToken = manager.generateRefreshToken(userId = 1)

            val response = client.post(AuthPaths.LOGOUT_ALL) { bearerAuth(refreshToken) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a token signed with a different secret is rejected`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }
            val otherManager =
                JwtTokenManager(
                    secret = "a-completely-different-secret",
                    audience = TEST_JWT_AUDIENCE,
                    issuer = TEST_JWT_ISSUER,
                )
            val forgedToken = otherManager.generateAccessToken(userId = 1)

            val response = client.post(AuthPaths.LOGOUT_ALL) { bearerAuth(forgedToken) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a malformed bearer token is rejected`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }

            val response = client.post(AuthPaths.LOGOUT_ALL) { header("Authorization", "Bearer not-a-real-jwt") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
