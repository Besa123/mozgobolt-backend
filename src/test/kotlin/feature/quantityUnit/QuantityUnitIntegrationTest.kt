package com.shelflife.feature.quantityUnit

import com.shelflife.core.skipIfNoDocker
import com.shelflife.feature.product.domain.model.UnitCategory
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the seeded, closed set of quantity units (V3 migration) against a real database —
 * see docs/domain/pantry.md decisions 1 and 6: PIECE only ever has "db" (no multiplier semantics,
 * since a box/jar/pack isn't a fixed piece count), and the unit catalog is fully seeded rather than
 * user-extensible like products are.
 */
class QuantityUnitIntegrationTest {
    @Test
    fun `the seeded catalog contains exactly the seven Hungarian units from the migration`() {
        skipIfNoDocker()

        withRealQuantityUnitDatabase { harness ->
            val units = harness.service.listAll()

            assertEquals(
                setOf("kg", "dkg", "g", "l", "dl", "ml", "db"),
                units.map { it.name }.toSet(),
            )
            assertEquals(7, units.size)
        }
    }

    @Test
    fun `PIECE category contains only db, with no other convertible piece unit`() {
        skipIfNoDocker()

        withRealQuantityUnitDatabase { harness ->
            val units = harness.service.listAll()

            val pieceUnits = units.filter { it.category == UnitCategory.PIECE }

            assertEquals(listOf("db"), pieceUnits.map { it.name })
        }
    }

    @Test
    fun `each seeded unit carries its documented multiplier toward its category's base unit`() {
        skipIfNoDocker()

        withRealQuantityUnitDatabase { harness ->
            val byName = harness.service.listAll().associateBy { it.name }

            assertEquals(0, BigDecimal("1000.0000").compareTo(byName.getValue("kg").multiplier))
            assertEquals(0, BigDecimal("10.0000").compareTo(byName.getValue("dkg").multiplier))
            assertEquals(0, BigDecimal("1.0000").compareTo(byName.getValue("g").multiplier))
            assertEquals(0, BigDecimal("1000.0000").compareTo(byName.getValue("l").multiplier))
            assertEquals(0, BigDecimal("100.0000").compareTo(byName.getValue("dl").multiplier))
            assertEquals(0, BigDecimal("1.0000").compareTo(byName.getValue("ml").multiplier))
            assertEquals(0, BigDecimal("1.0000").compareTo(byName.getValue("db").multiplier))
        }
    }

    @Test
    fun `each seeded unit's category matches its physical quantity type`() {
        skipIfNoDocker()

        withRealQuantityUnitDatabase { harness ->
            val byName = harness.service.listAll().associateBy { it.name }

            assertEquals(UnitCategory.MASS, byName.getValue("kg").category)
            assertEquals(UnitCategory.MASS, byName.getValue("dkg").category)
            assertEquals(UnitCategory.MASS, byName.getValue("g").category)
            assertEquals(UnitCategory.VOLUME, byName.getValue("l").category)
            assertEquals(UnitCategory.VOLUME, byName.getValue("dl").category)
            assertEquals(UnitCategory.VOLUME, byName.getValue("ml").category)
            assertEquals(UnitCategory.PIECE, byName.getValue("db").category)
        }
    }

    @Test
    fun `listAll is sorted by name ascending`() {
        skipIfNoDocker()

        withRealQuantityUnitDatabase { harness ->
            val names = harness.service.listAll().map { it.name }

            assertEquals(names.sorted(), names)
        }
    }

    @Test
    fun `findById returns the matching unit for a known id`() {
        skipIfNoDocker()

        withRealQuantityUnitDatabase { harness ->
            val kg = harness.service.listAll().single { it.name == "kg" }

            val found = harness.tx.transactional { harness.repository.findById(kg.id) }

            assertEquals(kg, found)
        }
    }

    @Test
    fun `findById returns null for an id outside the seeded set`() {
        skipIfNoDocker()

        withRealQuantityUnitDatabase { harness ->
            val found = harness.tx.transactional { harness.repository.findById(999_999) }

            assertNull(found)
        }
    }
}
