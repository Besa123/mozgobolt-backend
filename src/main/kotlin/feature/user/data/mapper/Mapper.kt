/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.mapper

import com.mozgobolt.feature.user.data.database.UserEntity
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.user.domain.model.User
import org.jetbrains.exposed.v1.core.ResultRow

fun UserEntity.toUser() =
    User(
        id = id.value,
        email = email,
        name = name,
        passwordHash = passwordHash,
        role = role,
        phoneNumber = phoneNumber,
        phoneNumberVisible = phoneNumberVisible,
        whatsappNumber = whatsappNumber,
        whatsappVisible = whatsappVisible,
        viberNumber = viberNumber,
        viberVisible = viberVisible,
        messengerUsername = messengerUsername,
        messengerVisible = messengerVisible,
        failedLoginAttempts = failedLoginAttempts,
        lockedUntil = lockedUntil,
        isEmailVerified = isEmailVerified,
    )

fun ResultRow.toUser() =
    User(
        id = this[UsersTable.id].value,
        email = this[UsersTable.email],
        name = this[UsersTable.name],
        passwordHash = this[UsersTable.passwordHash],
        role = this[UsersTable.role],
        phoneNumber = this[UsersTable.phoneNumber],
        phoneNumberVisible = this[UsersTable.phoneNumberVisible],
        whatsappNumber = this[UsersTable.whatsappNumber],
        whatsappVisible = this[UsersTable.whatsappVisible],
        viberNumber = this[UsersTable.viberNumber],
        viberVisible = this[UsersTable.viberVisible],
        messengerUsername = this[UsersTable.messengerUsername],
        messengerVisible = this[UsersTable.messengerVisible],
        failedLoginAttempts = this[UsersTable.failedLoginAttempts],
        lockedUntil = this[UsersTable.lockedUntil],
        isEmailVerified = this[UsersTable.isEmailVerified],
    )
