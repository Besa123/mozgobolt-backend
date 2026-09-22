package com.mozgobolt.feature.sync

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.sync.data.repository.SyncRepositoryI
import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.service.InMemorySyncEventHub
import com.mozgobolt.feature.sync.service.SyncServiceI

data class SyncTestHarness(
    val repository: SyncRepositoryI,
    // Not consumed by [service] (SyncServiceI has no SyncEventHub dependency — see its class
    // doc) — kept here for tests exercising PostgresNotifyListener's own NOTIFY-to-hub wiring.
    val eventHub: SyncEventHub,
    val service: SyncServiceI,
    val tx: TransactionalRunner,
)

fun withRealSyncDatabase(block: suspend (SyncTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val repository = SyncRepositoryI()
        val eventHub = InMemorySyncEventHub()
        val service = SyncServiceI(syncRepository = repository, tx = tx)
        block(SyncTestHarness(repository, eventHub, service, tx))
    }
}
