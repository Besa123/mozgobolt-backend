package com.besa.boardShare

import com.besa.boardShare.core.database.DatabaseFactory.runFlywayMigration
import com.besa.boardShare.core.di.configureDependencyInjection
import com.besa.boardShare.core.modules.configureModules
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import javax.sql.DataSource

fun Application.rootModule() {
    configureModules()
    configureDependencyInjection()
    configureRouting()

    val database: DataSource by dependencies
    runFlywayMigration(database)
}
