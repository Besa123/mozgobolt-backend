package com.shelflife.feature.quantityUnit

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.quantityUnit.data.repository.QuantityUnitRepositoryI
import com.shelflife.feature.quantityUnit.service.QuantityUnitServiceI

data class QuantityUnitTestHarness(
    val repository: QuantityUnitRepositoryI,
    val service: QuantityUnitServiceI,
    val tx: TransactionalRunner,
)

fun withRealQuantityUnitDatabase(block: suspend (QuantityUnitTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val repository = QuantityUnitRepositoryI()
        val service = QuantityUnitServiceI(quantityUnitRepository = repository, tx = tx)
        block(QuantityUnitTestHarness(repository, service, tx))
    }
}
