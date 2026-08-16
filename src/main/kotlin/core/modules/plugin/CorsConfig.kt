package com.besa.boardShare.core.modules.plugin

import com.besa.boardShare.core.modules.AppConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCors() {
    val corsConfig: AppConfig.Cors = property("app.cors")
    val env = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "local"

    install(CORS) {
        corsConfig.hosts().forEach { host ->
            allowHost(host, schemes = listOf("https"))
        }

        val isDev = env != "production"
        if (isDev) {
            allowHost("localhost:3000")
            allowHost("localhost:5173")
            allowHost("127.0.0.1:3000")
            allowHost("127.0.0.1:5173")
        }

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)

        exposeHeader(HttpHeaders.XRequestId)

        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)

        allowCredentials = true
        maxAgeInSeconds = 3600
    }
}