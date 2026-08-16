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
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

class UserRepositoryI : UserRepository {

    override suspend fun findUser(email: String): User? {
        return UserEntity.find { UsersTable.email eq email }.firstOrNull()?.toUser()
    }

    override suspend fun findUserById(userId: Int): User? {
        return UserEntity.find { UsersTable.id eq userId }.firstOrNull()?.toUser()
    }

    override suspend fun createUser(
        email: String,
        password: String,
        name: String
    ): User {
        val entity = UserEntity.new {
            this.email = email
            this.name = name
            this.passwordHash = password
        }
        return entity.toUser()
    }

    override suspend fun saveRefreshToken(userId: Int, token: String) {
        val now = Instant.now()
        RefreshTokenEntity.new {
            this.user = UserEntity[userId]
            this.token = token
            this.createdAt = now
            this.expiresAt = now.plus(Duration.ofDays(30))
        }
    }

    override suspend fun validateAndRevokeRefreshToken(userId: Int, token: String): Boolean {
        val now = Instant.now()
        val tokenRow = RefreshTokenEntity
            .find { (RefreshTokensTable.userId eq userId) and (RefreshTokensTable.token eq token) }
            .singleOrNull()

        if (tokenRow == null || tokenRow.isRevoked || tokenRow.expiresAt.isBefore(now)) {
            return false
        }

        RefreshTokenEntity.findByIdAndUpdate(tokenRow.id.value) {
            it.isRevoked = true
        }

        return true
    }

    override suspend fun revokeAllTokensForUser(userId: Int) {
        RefreshTokensTable.update(
            where = { RefreshTokensTable.userId eq userId }) {
            it[isRevoked] = true
        }
    }

    override suspend fun revokeSpecificRefreshToken(token: String) {
        RefreshTokensTable.update(
            where = { RefreshTokensTable.token eq token }) {
            it[isRevoked] = true
        }
    }
}