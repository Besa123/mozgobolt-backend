package com.besa.boardShare.feature.user.data.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object RefreshTokensTable : IntIdTable("refresh_tokens") {
    val userId = reference(
        name = "user_id",
        refColumn = UsersTable.id,
        onDelete = ReferenceOption.CASCADE
    )
    val token = varchar("token", 512).uniqueIndex()
    val isRevoked = bool("is_revoked").default(false)
}

class RefreshTokenEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RefreshTokenEntity>(RefreshTokensTable)

    var user by UserEntity referencedOn RefreshTokensTable.userId
    var token by RefreshTokensTable.token
    var isRevoked by RefreshTokensTable.isRevoked
}