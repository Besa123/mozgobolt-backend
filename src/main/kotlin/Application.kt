package com.besa.boardShare

import com.besa.boardShare.database.DatabaseFactory
import com.besa.boardShare.di.configureDependencyInjection
import com.besa.boardShare.modules.configureModules
import io.ktor.server.application.*

fun Application.rootModule() {
    DatabaseFactory.database
    configureModules()
    configureDependencyInjection()
    configureRouting()
}
