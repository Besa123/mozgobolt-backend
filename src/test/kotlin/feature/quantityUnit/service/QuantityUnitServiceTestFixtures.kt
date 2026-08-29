package com.shelflife.feature.quantityUnit.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.quantityUnit.domain.QuantityUnitRepository
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit

class NoopTransactionalRunner : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = block()
}

/** In-memory stand-in for [QuantityUnitRepository], seeded explicitly per test — no seed data baked in. */
class FakeQuantityUnitRepository : QuantityUnitRepository {
    private val unitsById = mutableMapOf<Int, QuantityUnit>()

    fun seed(unit: QuantityUnit) {
        unitsById[unit.id] = unit
    }

    override suspend fun findAll(): List<QuantityUnit> = unitsById.values.sortedBy { it.name }

    override suspend fun findById(id: Int): QuantityUnit? = unitsById[id]
}
