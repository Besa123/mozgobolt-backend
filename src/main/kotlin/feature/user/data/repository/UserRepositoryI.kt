package com.besa.shelflife.feature.user.data.repository

import com.besa.shelflife.feature.user.data.database.*
import com.besa.shelflife.feature.user.data.mapper.toUser
import com.besa.shelflife.feature.user.domain.UserRepository
import com.besa.shelflife.feature.user.domain.model.TokenValidationResult
import com.besa.shelflife.feature.user.domain.model.User
import com.besa.shelflife.feature.user.domain.model.VerificationTokenRecord
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

class UserRepositoryI : UserRepository {
    override suspend fun findUser(email: String): User? =
        UserEntity.find { UsersTable.email eq email }.firstOrNull()?.toUser()

    override suspend fun findUserById(userId: Int): User? =
        UserEntity.find { UsersTable.id eq userId }.firstOrNull()?.toUser()

    override suspend fun createUser(
        email: String,
        password: String,
        name: String,
    ): User {
        val entity =
            UserEntity.new {
                this.email = email
                this.name = name
                this.passwordHash = password
            }
        return entity.toUser()
    }

    override suspend fun saveRefreshToken(
        userId: Int,
        token: String,
        familyId: String,
    ) {
        val now = Instant.now()
        RefreshTokenEntity.new {
            this.user = UserEntity[userId]
            this.token = token
            this.familyId = familyId
            this.createdAt = now
            this.expiresAt = now.plus(Duration.ofDays(30))
        }
    }

    override suspend fun validateAndRevokeRefreshToken(
        userId: Int,
        token: String,
    ): TokenValidationResult {
        val now = Instant.now()
        val tokenRow =
            RefreshTokenEntity
                .find { (RefreshTokensTable.userId eq userId) and (RefreshTokensTable.token eq token) }
                .singleOrNull()
                ?: return TokenValidationResult.NotFound

        if (tokenRow.expiresAt.isBefore(now)) return TokenValidationResult.NotFound

        if (tokenRow.isRevoked) {
            return TokenValidationResult.AlreadyRevoked(familyId = tokenRow.familyId)
        }

        val updated =
            RefreshTokensTable.update(
                where = {
                    (RefreshTokensTable.id eq tokenRow.id.value) and
                        (RefreshTokensTable.isRevoked eq false)
                },
            ) {
                it[isRevoked] = true
            }

        if (updated == 0) {
            return TokenValidationResult.AlreadyRevoked(familyId = tokenRow.familyId)
        }

        return TokenValidationResult.Valid(familyId = tokenRow.familyId)
    }

    override suspend fun recordFailedLogin(
        userId: Int,
        lockUntil: Instant?,
    ) {
        UsersTable.update(where = { UsersTable.id eq userId }) {
            it[failedLoginAttempts] = failedLoginAttempts + 1
            it[UsersTable.lockedUntil] = lockUntil
        }
    }

    override suspend fun resetFailedLogins(userId: Int) {
        UsersTable.update(where = { UsersTable.id eq userId }) {
            it[failedLoginAttempts] = 0
            it[lockedUntil] = null
        }
    }

    override suspend fun revokeTokenFamily(familyId: String) {
        if (familyId.isBlank()) return
        RefreshTokensTable.update(
            where = { RefreshTokensTable.familyId eq familyId },
        ) {
            it[isRevoked] = true
        }
    }

    override suspend fun revokeAllTokensForUser(userId: Int) {
        RefreshTokensTable.update(
            where = { RefreshTokensTable.userId eq userId },
        ) {
            it[isRevoked] = true
        }
    }

    override suspend fun revokeSpecificRefreshToken(token: String) {
        RefreshTokensTable.update(
            where = { RefreshTokensTable.token eq token },
        ) {
            it[isRevoked] = true
        }
    }

    override suspend fun createVerificationToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    ) {
        EmailVerificationTokenEntity.new {
            this.user = UserEntity[userId]
            this.token = token
            this.expiresAt = expiresAt
            this.createdAt = Instant.now()
        }
    }

    override suspend fun findVerificationToken(token: String): VerificationTokenRecord? =
        EmailVerificationTokenEntity
            .find { EmailVerificationTokensTable.token eq token }
            .singleOrNull()
            ?.let { entity ->
                VerificationTokenRecord(
                    id = entity.id.value,
                    userId = entity.user.id.value,
                    token = entity.token,
                    expiresAt = entity.expiresAt,
                    used = entity.used,
                )
            }

    override suspend fun markTokenUsed(tokenId: Int) {
        EmailVerificationTokensTable.update(
            where = { EmailVerificationTokensTable.id eq tokenId },
        ) {
            it[used] = true
        }
    }

    override suspend fun markEmailVerified(userId: Int) {
        UsersTable.update(where = { UsersTable.id eq userId }) {
            it[isEmailVerified] = true
        }
    }

    override suspend fun invalidateVerificationTokens(userId: Int) {
        EmailVerificationTokensTable.update(
            where = { EmailVerificationTokensTable.userId eq userId },
        ) {
            it[used] = true
        }
    }
}
