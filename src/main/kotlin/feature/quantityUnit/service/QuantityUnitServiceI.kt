package com.shelflife.feature.quantityUnit.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.quantityUnit.domain.QuantityUnitRepository
import com.shelflife.feature.quantityUnit.domain.QuantityUnitService
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit

class QuantityUnitServiceI(
    private val quantityUnitRepository: QuantityUnitRepository,
    private val tx: TransactionalRunner,
) : QuantityUnitService {
    override suspend fun listAll(): List<QuantityUnit> = tx.transactional { quantityUnitRepository.findAll() }
}
