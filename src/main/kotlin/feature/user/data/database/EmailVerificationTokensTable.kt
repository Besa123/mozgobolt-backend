package com.mozgobolt.feature.user.data.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

object EmailVerificationTokensTable : IntIdTable("email_verification_tokens") {
    val userId =
        reference(
            name = "user_id",
            refColumn = UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
        ).index()
    val token = varchar("token", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    val createdAt = timestamp("created_at")
}

class EmailVerificationTokenEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var user by UserEntity referencedOn EmailVerificationTokensTable.userId
    var token by EmailVerificationTokensTable.token
    var expiresAt by EmailVerificationTokensTable.expiresAt
    var used by EmailVerificationTokensTable.used
    var createdAt by EmailVerificationTokensTable.createdAt

    companion object : IntEntityClass<EmailVerificationTokenEntity>(EmailVerificationTokensTable)
}
