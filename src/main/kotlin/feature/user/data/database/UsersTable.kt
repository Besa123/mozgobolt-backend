package com.besa.boardShare.feature.user.data.database

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

data object UsersTable : IntIdTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", 100)
    val passwordHash = varchar("password_hash", 255)

}

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserEntity>(UsersTable)

    var email by UsersTable.email
    var name by UsersTable.name
    var passwordHash by UsersTable.passwordHash
}