package com.shelflife.feature.product.data.database

import com.shelflife.feature.user.data.database.UserEntity
import com.shelflife.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object StorageLocationsTable : IntIdTable("storage_locations") {
    val userId =
        reference(
            name = "user_id",
            refColumn = UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).index()

    val name = varchar("name", 100)

    init {
        uniqueIndex("uq_user_location_name", userId, name)
    }
}

class StorageLocationEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var user by UserEntity referencedOn StorageLocationsTable.userId
    var name by StorageLocationsTable.name

    companion object : IntEntityClass<StorageLocationEntity>(StorageLocationsTable)
}
