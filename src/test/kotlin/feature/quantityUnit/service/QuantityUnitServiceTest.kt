package com.shelflife.feature.quantityUnit.service

import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit
import com.shelflife.feature.quantityUnit.domain.model.UnitCategory
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuantityUnitServiceTest {
    @Test
    fun `listAll returns every unit known to the repository`() =
        runBlocking {
            val repository = FakeQuantityUnitRepository()
            repository.seed(
                QuantityUnit(id = 1, name = "kg", category = UnitCategory.MASS, multiplier = BigDecimal("1000")),
            )
            repository.seed(
                QuantityUnit(id = 2, name = "db", category = UnitCategory.PIECE, multiplier = BigDecimal("1")),
            )
            val service = QuantityUnitServiceI(quantityUnitRepository = repository, tx = NoopTransactionalRunner())

            val units = service.listAll()

            assertEquals(setOf("kg", "db"), units.map { it.name }.toSet())
        }

    @Test
    fun `listAll returns an empty list when nothing is seeded`() =
        runBlocking {
            val repository = FakeQuantityUnitRepository()
            val service = QuantityUnitServiceI(quantityUnitRepository = repository, tx = NoopTransactionalRunner())

            val units = service.listAll()

            assertEquals(emptyList(), units)
        }

    @Test
    fun `listAll reflects the repository's sort order rather than insertion order`() =
        runBlocking {
            val repository = FakeQuantityUnitRepository()
            repository.seed(
                QuantityUnit(id = 1, name = "z-unit", category = UnitCategory.MASS, multiplier = BigDecimal("1")),
            )
            repository.seed(
                QuantityUnit(id = 2, name = "a-unit", category = UnitCategory.MASS, multiplier = BigDecimal("1")),
            )
            val service = QuantityUnitServiceI(quantityUnitRepository = repository, tx = NoopTransactionalRunner())

            val units = service.listAll()

            assertEquals(listOf("a-unit", "z-unit"), units.map { it.name })
        }

    @Test
    fun `an unknown id resolves to no unit via the repository directly`() =
        runBlocking {
            val repository = FakeQuantityUnitRepository()

            assertNull(repository.findById(999))
        }
}
