package com.besa.shelflife.feature.health.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.sql.DataSource

private const val STATUS_KEY = "status"

fun Route.infrastructureRoutes(dataSource: DataSource) {
    get("/health") {
        val dbHealthy = runCatching { dataSource.connection.use { it.isValid(2) } }.isSuccess

        if (dbHealthy) {
            call.respond(HttpStatusCode.OK, mapOf(STATUS_KEY to "healthy"))
        } else {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf(STATUS_KEY to "unhealthy", "reason" to "database"),
            )
        }
    }

    get("/ready") {
        call.respond(HttpStatusCode.OK, mapOf(STATUS_KEY to "ready"))
    }
}
