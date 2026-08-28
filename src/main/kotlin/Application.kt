package com.shelflife

import com.shelflife.core.database.DatabaseFactory.runFlywayMigration
import com.shelflife.core.di.configureDependencyInjection
import com.shelflife.core.modules.configureModules
import com.shelflife.feature.sync.di.configureSyncEventListener
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import javax.sql.DataSource

fun Application.rootModule() {
    configureDependencyInjection()
    configureModules()
    configureRouting()

    val database: DataSource by dependencies
    runFlywayMigration(database)

    configureSyncEventListener()
}
