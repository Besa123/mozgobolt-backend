package com.besa.boardShare.feature.user.data.repository

import com.besa.boardShare.feature.user.data.database.RefreshTokenEntity
import com.besa.boardShare.feature.user.data.database.RefreshTokensTable
import com.besa.boardShare.feature.user.data.database.UserEntity
import com.besa.boardShare.feature.user.data.database.UsersTable
import com.besa.boardShare.feature.user.data.mapper.toUser
import com.besa.boardShare.feature.user.domain.UserRepository
import com.besa.boardShare.feature.user.domain.model.User
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

class UserRepositoryI(
    private val database: Database
) : UserRepository {

    override suspend fun findUser(email: String) = suspendTransaction(
        db = database
    ) {
        UserEntity.find { UsersTable.email eq email }.firstOrNull()?.toUser()
    }

    override suspend fun findUserById(userId: Int): User? = suspendTransaction(
        db = database
    ) {
        UserEntity.find { UsersTable.id eq userId }.firstOrNull()?.toUser()
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
        val now = Instant.now()
        RefreshTokenEntity.new {
            this.user = UserEntity[userId]
            this.token = token
            this.createdAt = now
            this.expiresAt = now.plus(Duration.ofDays(30))
        }
    }

    override suspend fun validateAndRevokeRefreshToken(userId: Int, token: String): Boolean = suspendTransaction(
        db = database
    ) {
        val tokenRow = RefreshTokenEntity
            .find { (RefreshTokensTable.userId eq userId) and (RefreshTokensTable.token eq token) }
            .singleOrNull()

        if (tokenRow == null || tokenRow.isRevoked) {
            return@suspendTransaction false
        }

        RefreshTokenEntity.findByIdAndUpdate(tokenRow.id.value) {
            it.isRevoked = true
        }

        true

    }

    override suspend fun revokeAllTokensForUser(userId: Int): Unit = suspendTransaction(
        db = database
    ) {
        RefreshTokensTable.update(
            where = { RefreshTokensTable.userId eq userId }) {
            it[isRevoked] = true
        }
    }

    override suspend fun revokeSpecificRefreshToken(token: String): Unit = suspendTransaction(
        db = database
    ) {
        RefreshTokensTable.update(
            where = {
                RefreshTokensTable.token eq token
            }) {
            it[isRevoked] = true
        }
    }
}