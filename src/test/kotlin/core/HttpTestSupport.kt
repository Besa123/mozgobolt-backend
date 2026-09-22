package com.mozgobolt.core

import com.auth0.jwt.JWTVerifier
import com.mozgobolt.core.data.security.JwtTokenManager
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.modules.plugin.configureCallId
import com.mozgobolt.core.modules.plugin.configureCallLogging
import com.mozgobolt.core.modules.plugin.configureContentNegotiation
import com.mozgobolt.core.modules.plugin.configureCors
import com.mozgobolt.core.modules.plugin.configureDefaultHeaders
import com.mozgobolt.core.modules.plugin.configureForwardedHeaders
import com.mozgobolt.core.modules.plugin.configureGlobalBodyLimit
import com.mozgobolt.core.modules.plugin.configureHttpRequestLifecycle
import com.mozgobolt.core.modules.plugin.configureRateLimit
import com.mozgobolt.core.modules.plugin.configureRequestTimeout
import com.mozgobolt.core.modules.plugin.configureRequestValidation
import com.mozgobolt.core.modules.plugin.configureSecurity
import com.mozgobolt.core.modules.plugin.configureSse
import com.mozgobolt.core.modules.plugin.configureStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.resources.Resources
import io.ktor.server.testing.ApplicationTestBuilder

const val TEST_JWT_SECRET = "http-test-secret"
const val TEST_JWT_ISSUER = "http-test-issuer"
const val TEST_JWT_AUDIENCE = "http-test-audience"

private fun testJwtTokenManager(): JwtTokenManager =
    JwtTokenManager(secret = TEST_JWT_SECRET, audience = TEST_JWT_AUDIENCE, issuer = TEST_JWT_ISSUER)

fun testTokenManager(): TokenManager = testJwtTokenManager()

fun testAccessTokenFor(
    userId: Int,
    role: String = "BUYER",
): String = testJwtTokenManager().generateAccessToken(userId, role)

fun ApplicationTestBuilder.configureTestEnvironment(
    corsAllowedHosts: String = "",
    environmentName: String = "local",
) {
    environment {
        config =
            MapApplicationConfig(
                "app.cors.allowedHosts" to corsAllowedHosts,
                "ktor.environment" to environmentName,
            )
    }
}

fun Application.installTestModules() {
    install(Resources)
    install(AutoHeadResponse)
    configureDefaultHeaders()
    configureForwardedHeaders()
    configureGlobalBodyLimit()
    configureSse()
    configureRequestTimeout()
    dependencies {
        val tokenManager = testJwtTokenManager()
        provide<TokenManager> { tokenManager }
        provide<JWTVerifier> { tokenManager.accessTokenVerifier }
    }
    configureSecurity()
    configureCors()
    configureContentNegotiation()
    configureRateLimit()
    configureCallId()
    configureCallLogging()
    configureRequestValidation()
    configureStatusPages()
    configureHttpRequestLifecycle()
}
