package com.besa.shelflife.core.modules

import com.besa.shelflife.core.modules.plugin.configureCallId
import com.besa.shelflife.core.modules.plugin.configureCallLogging
import com.besa.shelflife.core.modules.plugin.configureContentNegotiation
import com.besa.shelflife.core.modules.plugin.configureCors
import com.besa.shelflife.core.modules.plugin.configureDefaultHeaders
import com.besa.shelflife.core.modules.plugin.configureForwardedHeaders
import com.besa.shelflife.core.modules.plugin.configureGlobalBodyLimit
import com.besa.shelflife.core.modules.plugin.configureHttpRequestLifecycle
import com.besa.shelflife.core.modules.plugin.configureRateLimit
import com.besa.shelflife.core.modules.plugin.configureRequestTimeout
import com.besa.shelflife.core.modules.plugin.configureRequestValidation
import com.besa.shelflife.core.modules.plugin.configureSecurity
import com.besa.shelflife.core.modules.plugin.configureStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.resources.Resources

fun Application.configureModules() {
    install(Resources)
    install(AutoHeadResponse)
    configureDefaultHeaders()
    configureForwardedHeaders()
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
