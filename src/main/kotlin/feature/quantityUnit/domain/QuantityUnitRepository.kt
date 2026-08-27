package com.shelflife.feature.quantityUnit.domain

import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit

interface QuantityUnitRepository {
    suspend fun findAll(): List<QuantityUnit>

    suspend fun findById(id: Int): QuantityUnit?
}
