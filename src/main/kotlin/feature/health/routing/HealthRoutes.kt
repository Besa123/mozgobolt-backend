package com.besa.shelflife.feature.health.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.sql.DataSource

fun Route.infrastructureRoutes(dataSource: DataSource) {
    get("/health") {
        val dbHealthy = runCatching { dataSource.connection.use { it.isValid(2) } }.isSuccess

        if (dbHealthy) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
        } else {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("status" to "unhealthy", "reason" to "database"),
            )
        }
    }

    get("/ready") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
    }
}
