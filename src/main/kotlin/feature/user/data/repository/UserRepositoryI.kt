package com.besa.boardShare.feature.user.data.repository

import com.besa.boardShare.feature.user.data.database.RefreshTokenEntity
import com.besa.boardShare.feature.user.data.database.UserEntity
import com.besa.boardShare.feature.user.data.database.UsersTable
import com.besa.boardShare.feature.user.data.mapper.toUser
import com.besa.boardShare.feature.user.domain.UserRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class UserRepositoryI(
    private val database: Database
) : UserRepository {

    override suspend fun findUser(email: String) = suspendTransaction(
        db = database
    ) {
        UserEntity.find { UsersTable.email eq email }.firstOrNull()?.toUser()
    }

    override suspend fun createUser(
        email: String,
        password: String,
        name: String
    ) = suspendTransaction(
        db = database
    ) {
        val entity = UserEntity.new {
            this.email = email
            this.name = name
            this.passwordHash = password
        }

        entity.toUser()
    }

    override suspend fun saveRefreshToken(userId: Int, token: String): Unit = suspendTransaction(
        db = database
    ) {
        RefreshTokenEntity.new {
            this.user = UserEntity[userId]
            this.token = token
        }
    }
}