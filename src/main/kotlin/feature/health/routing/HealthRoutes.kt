package com.shelflife.feature.health.routing

import com.shelflife.core.routing.dto.response.ErrorResponse
import com.shelflife.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}
private const val STATUS_KEY = "status"
private const val DB_VALIDATION_TIMEOUT_SECONDS = 2

fun Route.infrastructureRoutes(dataSource: DataSource) {
    get("/health") {
        val dbHealthy =
            runSuspendCatching {
                withContext(Dispatchers.IO) {
                    dataSource.connection.use { it.isValid(DB_VALIDATION_TIMEOUT_SECONDS) }
                }
            }.onFailure { logger.warn(it) { "Health check database probe failed" } }
                .getOrDefault(false)

        if (dbHealthy) {
            call.respond(HttpStatusCode.OK, mapOf(STATUS_KEY to "healthy"))
        } else {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(error = "DATABASE_UNAVAILABLE"),
            )
        }
    }

    get("/ready") {
        call.respond(HttpStatusCode.OK, mapOf(STATUS_KEY to "ready"))
    }
}
