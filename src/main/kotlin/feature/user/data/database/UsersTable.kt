package com.mozgobolt.feature.user.data.database

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.core.domain.validation.MESSENGER_USERNAME_MAX_LENGTH
import com.mozgobolt.core.domain.validation.PHONE_NUMBER_MAX_LENGTH
import com.mozgobolt.feature.user.domain.model.UserRole
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object UsersTable : IntIdTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", DISPLAY_NAME_MAX_LENGTH)
    val passwordHash = varchar("password_hash", 255)
    val role = enumerationByName<UserRole>("role", 20)
    val phoneNumber = varchar("phone_number", PHONE_NUMBER_MAX_LENGTH).nullable()
    val phoneNumberVisible = bool("phone_number_visible").default(false)
    val whatsappNumber = varchar("whatsapp_number", PHONE_NUMBER_MAX_LENGTH).nullable()
    val whatsappVisible = bool("whatsapp_visible").default(false)
    val viberNumber = varchar("viber_number", PHONE_NUMBER_MAX_LENGTH).nullable()
    val viberVisible = bool("viber_visible").default(false)
    val messengerUsername = varchar("messenger_username", MESSENGER_USERNAME_MAX_LENGTH).nullable()
    val messengerVisible = bool("messenger_visible").default(false)
    val failedLoginAttempts = integer("failed_login_attempts").default(0)
    val lockedUntil = timestamp("locked_until").nullable()
    val isEmailVerified = bool("is_email_verified").default(false)
}

class UserEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var email by UsersTable.email
    var name by UsersTable.name
    var passwordHash by UsersTable.passwordHash
    var role by UsersTable.role
    var phoneNumber by UsersTable.phoneNumber
    var phoneNumberVisible by UsersTable.phoneNumberVisible
    var whatsappNumber by UsersTable.whatsappNumber
    var whatsappVisible by UsersTable.whatsappVisible
    var viberNumber by UsersTable.viberNumber
    var viberVisible by UsersTable.viberVisible
    var messengerUsername by UsersTable.messengerUsername
    var messengerVisible by UsersTable.messengerVisible
    var failedLoginAttempts by UsersTable.failedLoginAttempts
    var lockedUntil by UsersTable.lockedUntil
    var isEmailVerified by UsersTable.isEmailVerified

    companion object : IntEntityClass<UserEntity>(UsersTable)
}
