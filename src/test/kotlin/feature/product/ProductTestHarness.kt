package com.shelflife.feature.product

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.product.data.repository.ProductRepositoryI
import com.shelflife.feature.product.service.ProductServiceI
import com.shelflife.feature.sync.data.repository.SyncRepositoryI
import com.shelflife.feature.sync.service.SyncServiceI

data class ProductTestHarness(
    val repository: ProductRepositoryI,
    val service: ProductServiceI,
    val tx: TransactionalRunner,
)

fun withRealProductDatabase(block: suspend (ProductTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val repository = ProductRepositoryI()
        val syncService = SyncServiceI(SyncRepositoryI(), tx)
        val service = ProductServiceI(productRepository = repository, syncService = syncService, tx = tx)
        block(ProductTestHarness(repository, service, tx))
    }
}
