package com.besa.shelflife.feature.user.data.mapper

import com.besa.shelflife.feature.user.data.database.UserEntity
import com.besa.shelflife.feature.user.domain.model.User

fun UserEntity.toUser() = User(
    id = id.value,
    email = email,
    name = name,
    passwordHash = passwordHash,
    failedLoginAttempts = failedLoginAttempts,
    lockedUntil = lockedUntil,
    isEmailVerified = isEmailVerified,
)