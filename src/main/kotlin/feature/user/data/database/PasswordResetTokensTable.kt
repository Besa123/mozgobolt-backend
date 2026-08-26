package com.shelflife.feature.user.data.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

object PasswordResetTokensTable : IntIdTable("password_reset_tokens") {
    val userId =
        reference(
            name = "user_id",
            refColumn = UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).index()
    val token = varchar("token", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    val usedAt = timestamp("used_at").nullable()
    val createdAt = timestamp("created_at")
}

class PasswordResetTokenEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var user by UserEntity referencedOn PasswordResetTokensTable.userId
    var token by PasswordResetTokensTable.token
    var expiresAt by PasswordResetTokensTable.expiresAt
    var used by PasswordResetTokensTable.used
    var usedAt by PasswordResetTokensTable.usedAt
    var createdAt by PasswordResetTokensTable.createdAt

    companion object : IntEntityClass<PasswordResetTokenEntity>(PasswordResetTokensTable)
}
