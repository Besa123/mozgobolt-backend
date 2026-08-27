package com.shelflife.feature.quantityUnit.routing

import com.shelflife.feature.quantityUnit.domain.QuantityUnitService
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit

class FakeQuantityUnitService : QuantityUnitService {
    var listResult: List<QuantityUnit> = emptyList()

    override suspend fun listAll(): List<QuantityUnit> = listResult
}
