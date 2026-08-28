package com.shelflife.feature.storageLocation

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.storageLocation.data.repository.StorageLocationRepositoryI
import com.shelflife.feature.storageLocation.service.StorageLocationServiceI
import com.shelflife.feature.sync.data.repository.SyncRepositoryI
import com.shelflife.feature.sync.service.SyncServiceI

data class StorageLocationTestHarness(
    val repository: StorageLocationRepositoryI,
    val service: StorageLocationServiceI,
    val tx: TransactionalRunner,
)

fun withRealStorageLocationDatabase(block: suspend (StorageLocationTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val repository = StorageLocationRepositoryI()
        val syncService = SyncServiceI(SyncRepositoryI(), tx)
        val service =
            StorageLocationServiceI(storageLocationRepository = repository, syncService = syncService, tx = tx)
        block(StorageLocationTestHarness(repository, service, tx))
    }
}
