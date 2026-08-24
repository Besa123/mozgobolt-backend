package com.besa.shelflife

import com.besa.shelflife.core.data.idempotency.IdempotencyStore
import com.besa.shelflife.core.routing.apiV1
import com.besa.shelflife.feature.health.routing.infrastructureRoutes
import com.besa.shelflife.feature.user.domain.UserService
import com.besa.shelflife.feature.user.routing.authRoutes
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