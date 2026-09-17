package com.shelflife.feature.product.data.database

import com.shelflife.feature.quantityUnit.domain.model.UnitCategory
import com.shelflife.feature.user.data.database.UserEntity
import com.shelflife.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * Hybrid dictionary: `userId == null` is global, else a private override. Case-insensitive
 * uniqueness of [name] is enforced only in the V3 migration (partial index, not expressible in
 * Exposed) — see docs/domain/pantry.md.
 */
object ProductsTable : IntIdTable("products") {
    val userId =
        reference(
            name = "user_id",
            refColumn = UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).nullable().index()

    val name = varchar("name", 100)
    val defaultLifespanDays = integer("default_lifespan_days").nullable()
    val defaultUnitCategory = enumerationByName("default_unit_category", 20, UnitCategory::class).nullable()
}

class ProductEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var name by ProductsTable.name
    var user by UserEntity optionalReferencedOn ProductsTable.userId
    var defaultLifespanDays by ProductsTable.defaultLifespanDays
    var defaultUnitCategory by ProductsTable.defaultUnitCategory

    companion object : IntEntityClass<ProductEntity>(ProductsTable)
}
