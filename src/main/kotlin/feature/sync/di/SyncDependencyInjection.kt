package com.shelflife.feature.sync.di

import com.shelflife.core.modules.AppConfig
import com.shelflife.core.modules.plugin.exemptFromRequestTimeout
import com.shelflife.core.routing.API_V1_PREFIX
import com.shelflife.feature.sync.data.repository.SyncRepositoryI
import com.shelflife.feature.sync.domain.SyncEventHub
import com.shelflife.feature.sync.domain.SyncRepository
import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.routing.SYNC_LIVE_PATH
import com.shelflife.feature.sync.service.InMemorySyncEventHub
import com.shelflife.feature.sync.service.PostgresNotifyListener
import com.shelflife.feature.sync.service.SyncServiceI
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
