package com.besa.boardShare

import com.besa.boardShare.feature.health.routing.healthRoutes
import com.besa.boardShare.feature.user.routing.userRoutes
import io.ktor.server.application.*

fun Application.configureRouting() {
    healthRoutes()
    userRoutes()
}