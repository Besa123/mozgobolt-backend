package com.mozgobolt.feature.sync.di

import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.modules.plugin.exemptFromRequestTimeout
import com.mozgobolt.core.routing.API_V1_PREFIX
import com.mozgobolt.feature.sync.data.repository.SyncRepositoryI
import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.domain.SyncRepository
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.routing.SYNC_LIVE_PATH
import com.mozgobolt.feature.sync.service.InMemorySyncEventHub
import com.mozgobolt.feature.sync.service.PostgresNotifyListener
import com.mozgobolt.feature.sync.service.SyncServiceI
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configureSyncDependencyInjection() {
    exemptFromRequestTimeout("$API_V1_PREFIX$SYNC_LIVE_PATH")

    dependencies {
        provide<SyncRepository> { SyncRepositoryI() }
        provide<SyncEventHub> { InMemorySyncEventHub() }
        provide<SyncService>(::SyncServiceI)

        provide<PostgresNotifyListener> {
            val appConfig = resolve<AppConfig>()
            PostgresNotifyListener(
                dbUrl = appConfig.database.url,
                dbUser = appConfig.database.user,
                dbPassword = appConfig.database.password,
                syncEventHub = resolve<SyncEventHub>(),
            )
        }
    }
}

fun Application.configureSyncEventListener() {
    val listener: PostgresNotifyListener by dependencies
    listener.start()

    monitor.subscribe(ApplicationStopped) {
        listener.stop()
    }
}
