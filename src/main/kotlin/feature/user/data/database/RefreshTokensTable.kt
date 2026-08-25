package com.shelflife.feature.user.data.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

object RefreshTokensTable : IntIdTable("refresh_tokens") {
    val userId =
        reference(
            name = "user_id",
            refColumn = UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).index()
    val token = varchar("token", 64)
    val familyId = varchar("family_id", 36).index()
    val isRevoked = bool("is_revoked").default(false)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
}

class RefreshTokenEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var user by UserEntity referencedOn RefreshTokensTable.userId
    var token by RefreshTokensTable.token
    var familyId by RefreshTokensTable.familyId
    var isRevoked by RefreshTokensTable.isRevoked
    var createdAt by RefreshTokensTable.createdAt
    var expiresAt by RefreshTokensTable.expiresAt

    companion object : IntEntityClass<RefreshTokenEntity>(RefreshTokensTable)
}
