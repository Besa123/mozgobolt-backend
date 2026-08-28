package com.shelflife.feature.sync

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.sync.data.repository.SyncRepositoryI
import com.shelflife.feature.sync.domain.SyncEventHub
import com.shelflife.feature.sync.service.InMemorySyncEventHub
import com.shelflife.feature.sync.service.SyncServiceI

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
