package com.shelflife.core.modules

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
import com.shelflife.core.modules.plugin.configureStatusPages
import com.shelflife.core.modules.plugin.configureSwagger
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.resources.Resources

fun Application.configureModules() {
    install(Resources)
    install(AutoHeadResponse)
    configureDefaultHeaders()
    configureForwardedHeaders()
    configureSwagger()
    configureGlobalBodyLimit()
    configureRequestTimeout()
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
