package com.shelflife.core

import com.shelflife.core.data.security.JwtTokenManager
import com.shelflife.core.domain.security.TokenManager
import com.shelflife.core.modules.plugin.configureCallId
import com.shelflife.core.modules.plugin.configureCallLogging
import com.shelflife.core.modules.plugin.configureContentNegotiation
import com.shelflife.core.modules.plugin.configureCors
import com.shelflife.core.modules.plugin.configureDefaultHeaders
import com.shelflife.core.modules.plugin.configureForwardedHeaders
import com.shelflife.core.modules.plugin.configureGlobalBodyLimit
import com.shelflife.core.modules.plugin.configureHttpRequestLifecycle
import com.shelflife.core.modules.plugin.configureRateLimit
import com.shelflife.core.modules.plugin.configureRequestTimeout
import com.shelflife.core.modules.plugin.configureRequestValidation
import com.shelflife.core.modules.plugin.configureSecurity
import com.shelflife.core.modules.plugin.configureSse
import com.shelflife.core.modules.plugin.configureStatusPages
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

fun testTokenManager(): TokenManager =
    JwtTokenManager(secret = TEST_JWT_SECRET, audience = TEST_JWT_AUDIENCE, issuer = TEST_JWT_ISSUER)

fun testAccessTokenFor(userId: Int): String = testTokenManager().generateAccessToken(userId)

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
        provide<TokenManager> { testTokenManager() }
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
