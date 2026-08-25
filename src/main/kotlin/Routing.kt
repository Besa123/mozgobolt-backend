package com.shelflife

import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.routing.apiV1
import com.shelflife.feature.health.routing.infrastructureRoutes
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.routing.authRoutes
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
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
