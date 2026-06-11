package com.besa.boardShare.feature.user.data.mapper

import com.besa.boardShare.feature.user.data.database.UserEntity
import com.besa.boardShare.feature.user.domain.model.User

fun UserEntity.toUser() = User(
    id = id.value,
    email = email,
    name = name,
    passwordHash = passwordHash,
    passwordSalt = passwordSalt,
)