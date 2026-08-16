package com.besa.boardShare.feature.health.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import javax.sql.DataSource

fun Application.healthRoutes() {
    val dataSource: DataSource by dependencies

    routing {
        get("/health") {
            val dbHealthy = runCatching { dataSource.connection.use { it.isValid(2) } }.isSuccess

            if (dbHealthy) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("status" to "unhealthy", "reason" to "database")
                )
            }
        }

        get("/ready") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
        }
    }
}
