package com.shelflife.feature.pantryEntryImage.data.database

import com.shelflife.feature.product.data.database.PantryEntriesTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

object PantryEntryImagesTable : IntIdTable("pantry_entry_images") {
    val pantryEntryId =
        reference(
            name = "pantry_entry_id",
            refColumn = PantryEntriesTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).index()

    val storageKey = varchar("storage_key", 255).uniqueIndex()
    val contentType = varchar("content_type", 50)
    val sizeBytes = integer("size_bytes")
    val width = integer("width")
    val height = integer("height")
    val createdAt = timestamp("created_at")
}
