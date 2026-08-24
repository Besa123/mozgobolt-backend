package com.besa.shelflife

import com.besa.shelflife.core.database.DatabaseFactory.runFlywayMigration
import com.besa.shelflife.core.di.configureDependencyInjection
import com.besa.shelflife.core.modules.configureModules
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import javax.sql.DataSource

fun Application.rootModule() {
    configureDependencyInjection()
    configureModules()
    configureRouting()

    val database: DataSource by dependencies
    runFlywayMigration(database)
}
