package com.shelflife.feature.quantityUnit.data.repository

import com.shelflife.feature.product.data.database.QuantityUnitEntity
import com.shelflife.feature.product.data.database.QuantityUnitsTable
import com.shelflife.feature.quantityUnit.data.mapper.toQuantityUnit
import com.shelflife.feature.quantityUnit.domain.QuantityUnitRepository
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit
import org.jetbrains.exposed.v1.core.SortOrder

class QuantityUnitRepositoryI : QuantityUnitRepository {
    override suspend fun findAll(): List<QuantityUnit> =
        QuantityUnitEntity
            .all()
            .orderBy(QuantityUnitsTable.name to SortOrder.ASC)
            .map { it.toQuantityUnit() }

    override suspend fun findById(id: Int): QuantityUnit? = QuantityUnitEntity.findById(id)?.toQuantityUnit()
}
