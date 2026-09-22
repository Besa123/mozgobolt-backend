package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.modules.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.sentry.Sentry
import io.sentry.SentryOptions

private const val PRODUCTION_ENVIRONMENT = "production"
private val SENTRY_CONTEXT_TAGS = listOf("userId", "requestId", "deviceId", "method", "path")

fun Application.configureSentry() {
    val appConfig: AppConfig by dependencies
    if (appConfig.sentry.dsn.isBlank()) return

    val runtimeEnvironment = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "local"

    Sentry.init { options ->
        options.dsn = appConfig.sentry.dsn
        options.environment = runtimeEnvironment
        SENTRY_CONTEXT_TAGS.forEach { options.addContextTag(it) }
        options.isAttachStacktrace = true
        options.isDebug = runtimeEnvironment != PRODUCTION_ENVIRONMENT
        options.beforeSend =
            SentryOptions.BeforeSendCallback { event, _ ->
                @Suppress("UNCHECKED_CAST")
                (event.contexts["MDC"] as? MutableMap<String, String>)?.remove("remoteHost")
                event
            }
    }
}
