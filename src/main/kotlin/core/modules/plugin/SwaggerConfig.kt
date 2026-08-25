package com.shelflife.core.modules.plugin

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Serves Swagger UI at `/swagger-ui` and the OpenAPI spec at `/openapi.json`.
 *
 * The spec (`resources/openapi/documentation.json`) is hand-maintained, not generated —
 * edit it alongside route changes so it stays reviewable in PRs.
 */
fun Application.configureSwagger() {
    routing {
        get("/openapi.json") {
            val specFile = this.javaClass.classLoader.getResourceAsStream("openapi/documentation.json")
            val spec = specFile?.bufferedReader()?.use { it.readText() } ?: "{}"
            call.respondText(spec, ContentType.Application.Json)
        }

        get("/swagger-ui") {
            val htmlFile = this.javaClass.classLoader.getResourceAsStream("swagger-ui.html")
            val html = htmlFile?.bufferedReader()?.use { it.readText() } ?: ""
            call.respondText(html, ContentType.Text.Html)
        }
    }
}
