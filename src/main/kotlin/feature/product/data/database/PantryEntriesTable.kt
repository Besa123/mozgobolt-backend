package com.shelflife.feature.product.data.database

import com.shelflife.feature.user.data.database.UserEntity
import com.shelflife.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The physical inventory. Adding a purchase always inserts a new row here, even if one already
 * matches exactly — merging into [quantityAmount] is a deliberate user edit, never automatic. See
 * docs/domain/pantry.md.
 */
object PantryEntriesTable : IntIdTable("pantry_entries") {
    val userId =
        reference(
            name = "user_id",
            refColumn = UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).index()

    val productId =
        reference(
            name = "product_id",
            refColumn = ProductsTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).index()

    val storageLocationId =
        reference(
            name = "storage_location_id",
            refColumn = StorageLocationsTable.id,
            onDelete = ReferenceOption.SET_NULL,
        ).nullable().index()

    val unitId =
        reference(
            name = "unit_id",
            refColumn = QuantityUnitsTable.id,
            onDelete = ReferenceOption.RESTRICT,
        )

    val quantityAmount = decimal("quantity_amount", precision = 10, scale = 2)
    val expirationDate = date("expiration_date").nullable()
    val brandOrNote = varchar("brand_or_note", length = 255).nullable()
    val createdAt = timestamp("created_at")
}

class PantryEntryEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var user by UserEntity referencedOn PantryEntriesTable.userId
    var product by ProductEntity referencedOn PantryEntriesTable.productId
    var storageLocation by StorageLocationEntity optionalReferencedOn PantryEntriesTable.storageLocationId
    var unit by QuantityUnitEntity referencedOn PantryEntriesTable.unitId
    var quantityAmount by PantryEntriesTable.quantityAmount
    var expirationDate by PantryEntriesTable.expirationDate
    var brandOrNote by PantryEntriesTable.brandOrNote
    var createdAt by PantryEntriesTable.createdAt

    companion object : IntEntityClass<PantryEntryEntity>(PantryEntriesTable)
}
