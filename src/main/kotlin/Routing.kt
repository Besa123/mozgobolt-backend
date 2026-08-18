package com.besa.boardShare

import com.besa.boardShare.core.data.idempotency.IdempotencyStore
import com.besa.boardShare.core.routing.apiV1
import com.besa.boardShare.feature.health.routing.infrastructureRoutes
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.routing.authRoutes
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import javax.sql.DataSource

fun Application.configureRouting() {
    val userService: UserService by dependencies
    val idempotencyStore: IdempotencyStore by dependencies
    val dataSource: DataSource by dependencies

    routing {
        infrastructureRoutes(dataSource)

        apiV1 {
            authRoutes(userService, idempotencyStore)
        }
    }
}