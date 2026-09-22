package com.mozgobolt.feature.user.data.mapper

import com.mozgobolt.feature.user.data.database.UserEntity
import com.mozgobolt.feature.user.domain.model.User

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
