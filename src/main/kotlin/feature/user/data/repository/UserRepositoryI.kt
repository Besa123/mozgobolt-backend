package com.mozgobolt.feature.user.data.repository

import com.mozgobolt.feature.user.data.database.EmailVerificationTokenEntity
import com.mozgobolt.feature.user.data.database.EmailVerificationTokensTable
import com.mozgobolt.feature.user.data.database.PasswordResetTokenEntity
import com.mozgobolt.feature.user.data.database.PasswordResetTokensTable
import com.mozgobolt.feature.user.data.database.RefreshTokenEntity
import com.mozgobolt.feature.user.data.database.RefreshTokensTable
import com.mozgobolt.feature.user.data.database.UserEntity
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.user.data.mapper.toUser
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.model.PasswordResetToken
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.User
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerificationTokenRecord
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

@Suppress("TooManyFunctions")
class UserRepositoryI(
    private val refreshTokenExpiration: Duration = DEFAULT_REFRESH_TOKEN_EXPIRATION,
) : UserRepository {
    override suspend fun findUser(email: String): User? =
        UserEntity.find { UsersTable.email eq email }.firstOrNull()?.toUser()

    override suspend fun findUserById(userId: Int): User? =
        UserEntity
            .find {
                UsersTable.id eq userId
            }.firstOrNull()
            ?.toUser()

    override suspend fun createUser(
        email: String,
        password: String,
        name: String,
        role: UserRole,
        phoneNumber: String?,
        whatsappNumber: String?,
        viberNumber: String?,
        messengerUsername: String?,
    ): User {
        val entity =
            UserEntity.new {
                this.email = email
                this.name = name
                this.passwordHash = password
                this.role = role
                this.phoneNumber = phoneNumber
                this.whatsappNumber = whatsappNumber
                this.viberNumber = viberNumber
                this.messengerUsername = messengerUsername
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
            this.expiresAt = now.plus(refreshTokenExpiration.toJavaDuration())
        }
    }

    @Suppress("ReturnCount")
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

    override suspend fun revokeSpecificRefreshToken(
        userId: Int,
        token: String,
    ) {
        RefreshTokensTable.update(
            where = { (RefreshTokensTable.userId eq userId) and (RefreshTokensTable.token eq token) },
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

    override suspend fun markTokenUsed(tokenId: Int): Boolean {
        val updated =
            EmailVerificationTokensTable.update(
                where = {
                    (EmailVerificationTokensTable.id eq tokenId) and
                        (EmailVerificationTokensTable.used eq false)
                },
            ) {
                it[used] = true
            }
        return updated > 0
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

    override suspend fun createPasswordResetToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    ) {
        PasswordResetTokenEntity.new {
            this.user = UserEntity[userId]
            this.token = token
            this.expiresAt = expiresAt
            this.createdAt = Instant.now()
        }
    }

    override suspend fun findPasswordResetToken(token: String): PasswordResetToken? =
        PasswordResetTokenEntity
            .find { PasswordResetTokensTable.token eq token }
            .singleOrNull()
            ?.let { entity ->
                PasswordResetToken(
                    id = entity.id.value,
                    userId = entity.user.id.value,
                    token = entity.token,
                    expiresAt = entity.expiresAt,
                    used = entity.used,
                    usedAt = entity.usedAt,
                    createdAt = entity.createdAt,
                )
            }

    override suspend fun markPasswordResetTokenUsed(
        tokenId: Int,
        usedAt: Instant,
    ): Boolean {
        val updated =
            PasswordResetTokensTable.update(
                where = {
                    (PasswordResetTokensTable.id eq tokenId) and
                        (PasswordResetTokensTable.used eq false)
                },
            ) {
                it[used] = true
                it[PasswordResetTokensTable.usedAt] = usedAt
            }
        return updated > 0
    }

    override suspend fun invalidatePasswordResetTokens(userId: Int) {
        PasswordResetTokensTable.update(
            where = { PasswordResetTokensTable.userId eq userId },
        ) {
            it[used] = true
        }
    }

    override suspend fun updatePassword(
        userId: Int,
        newPasswordHash: String,
    ) {
        UsersTable.update(where = { UsersTable.id eq userId }) {
            it[passwordHash] = newPasswordHash
        }
    }

    override suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    ) {
        UsersTable.update(where = { UsersTable.id eq userId }) {
            it[phoneNumber] = contactInfo.phoneNumber
            it[phoneNumberVisible] = contactInfo.phoneNumberVisible
            it[whatsappNumber] = contactInfo.whatsappNumber
            it[whatsappVisible] = contactInfo.whatsappVisible
            it[viberNumber] = contactInfo.viberNumber
            it[viberVisible] = contactInfo.viberVisible
            it[messengerUsername] = contactInfo.messengerUsername
            it[messengerVisible] = contactInfo.messengerVisible
        }
    }

    companion object {
        private val DEFAULT_REFRESH_TOKEN_EXPIRATION = 30.days
    }
}
