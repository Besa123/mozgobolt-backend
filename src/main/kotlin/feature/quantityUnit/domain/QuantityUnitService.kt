package com.shelflife.feature.quantityUnit.domain

import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit

interface QuantityUnitService {
    suspend fun listAll(): List<QuantityUnit>
}
