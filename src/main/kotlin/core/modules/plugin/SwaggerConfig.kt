package com.shelflife.core.modules.plugin

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Configure Swagger UI for interactive API documentation.
 *
 * The Swagger UI is available at `/swagger-ui` and reads the OpenAPI spec from
 * `src/main/resources/openapi/documentation.json`. The HTML template is served from
 * `src/main/resources/swagger-ui.html`.
 *
 * To update the API documentation, edit the OpenAPI spec file rather than
 * regenerating it — this keeps the documentation under source control and
 * easy to review in PRs.
 */
fun Application.configureSwagger() {
    routing {
        // Serve the OpenAPI spec
        get("/openapi.json") {
            val specFile = this.javaClass.classLoader.getResourceAsStream("openapi/documentation.json")
            val spec = specFile?.bufferedReader()?.use { it.readText() } ?: "{}"
            call.respondText(spec, ContentType.Application.Json)
        }

        // Serve Swagger UI
        get("/swagger-ui") {
            val htmlFile = this.javaClass.classLoader.getResourceAsStream("swagger-ui.html")
            val html = htmlFile?.bufferedReader()?.use { it.readText() } ?: ""
            call.respondText(html, ContentType.Text.Html)
        }
    }
}
