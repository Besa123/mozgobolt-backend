package com.besa.boardShare

import com.besa.boardShare.core.database.DatabaseFactory.schemaInitializer
import com.besa.boardShare.core.di.configureDependencyInjection
import com.besa.boardShare.core.modules.configureModules
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import org.jetbrains.exposed.v1.jdbc.Database

fun Application.rootModule() {
    configureModules()
    configureDependencyInjection()

    val database: Database by dependencies
    schemaInitializer(database)

    configureRouting()
}
