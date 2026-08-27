package com.shelflife.feature.product.data.database

import com.shelflife.feature.product.domain.model.UnitCategory
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * Pre-seeded (V3 migration) Hungarian units. [multiplier] converts to the category's base unit
 * (MASS -> g, VOLUME -> ml, PIECE -> db) for same-category aggregation. PIECE deliberately seeds
 * only "db" — see docs/domain/pantry.md.
 */
object QuantityUnitsTable : IntIdTable("quantity_units") {
    val name = varchar("name", 20)
    val category = enumerationByName("category", 20, UnitCategory::class)
    val multiplier = decimal("multiplier", precision = 10, scale = 4)
}

class QuantityUnitEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var name by QuantityUnitsTable.name
    var category by QuantityUnitsTable.category
    var multiplier by QuantityUnitsTable.multiplier

    companion object : IntEntityClass<QuantityUnitEntity>(QuantityUnitsTable)
}
