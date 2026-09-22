package com.mozgobolt.core.modules

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
import com.mozgobolt.core.modules.plugin.configureSentry
import com.mozgobolt.core.modules.plugin.configureSse
import com.mozgobolt.core.modules.plugin.configureStatusPages
import com.mozgobolt.core.modules.plugin.configureSwagger
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.resources.Resources

fun Application.configureModules() {
    configureSentry()
    install(Resources)
    install(AutoHeadResponse)
    configureDefaultHeaders()
    configureForwardedHeaders()
    configureSwagger()
    configureGlobalBodyLimit()
    configureSse()
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
