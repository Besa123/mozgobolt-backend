package com.besa.boardShare.feature.user.data.database

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

import org.jetbrains.exposed.v1.javatime.timestamp

data object UsersTable : IntIdTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", 100)
    val passwordHash = varchar("password_hash", 255)
    val failedLoginAttempts = integer("failed_login_attempts").default(0)
    val lockedUntil = timestamp("locked_until").nullable()
}

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserEntity>(UsersTable)

    var email by UsersTable.email
    var name by UsersTable.name
    var passwordHash by UsersTable.passwordHash
    var failedLoginAttempts by UsersTable.failedLoginAttempts
    var lockedUntil by UsersTable.lockedUntil
}