package com.shelflife.feature.storageLocation

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.storageLocation.data.repository.StorageLocationRepositoryI
import com.shelflife.feature.storageLocation.service.StorageLocationServiceI

data class StorageLocationTestHarness(
    val repository: StorageLocationRepositoryI,
    val service: StorageLocationServiceI,
    val tx: TransactionalRunner,
)

fun withRealStorageLocationDatabase(block: suspend (StorageLocationTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val repository = StorageLocationRepositoryI()
        val service = StorageLocationServiceI(storageLocationRepository = repository, tx = tx)
        block(StorageLocationTestHarness(repository, service, tx))
    }
}
