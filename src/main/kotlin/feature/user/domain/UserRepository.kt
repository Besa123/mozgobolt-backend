/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain

import com.mozgobolt.feature.user.domain.model.PasswordResetToken
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.User
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerificationTokenRecord
import java.time.Instant

/** Persistence boundary for user accounts, authentication tokens, and verification state. */
@Suppress("TooManyFunctions", "ComplexInterface")
interface UserRepository {
    /** Finds the account associated with an email address. */
    suspend fun findUser(email: String): User?

    /** Finds an account by its identifier. */
    suspend fun findUserById(userId: Int): User?

    /**
     * Reads the current account state while locking its database row. Call only inside the
     * transaction that will make the related credential or token-state change.
     */
    suspend fun findUserByIdForUpdate(userId: Int): User?

    /** Deletes the account and returns whether a row was removed. */
    suspend fun deleteUser(userId: Int): Boolean

    /** Removes expired authentication tokens and returns the number of rows removed. */
    suspend fun purgeExpiredAuthenticationTokens(
        now: Instant,
        oneTimeTokenRetentionCutoff: Instant,
    ): Int

    /**
     * Atomically inserts a user, returning null when the normalized email already exists.
     * Database uniqueness, rather than a preceding lookup, resolves concurrent registrations.
     */
    suspend fun createUser(
        email: String,
        password: String,
        name: String,
        role: UserRole,
        phoneNumber: String? = null,
        whatsappNumber: String? = null,
        viberNumber: String? = null,
        messengerUsername: String? = null,
    ): User?

    /** Stores a refresh token and returns its effective expiration time. */
    suspend fun saveRefreshToken(
        userId: Int,
        token: String,
        familyId: String,
        familyExpiresAt: Instant? = null,
    ): Instant

    /** Validates a refresh token and atomically revokes it when accepted. */
    suspend fun validateAndRevokeRefreshToken(
        userId: Int,
        token: String,
    ): TokenValidationResult

    /** Reports whether a refresh-token family remains active at [now]. */
    suspend fun isTokenFamilyActive(
        userId: Int,
        familyId: String,
        now: Instant,
    ): Boolean

    /** Records a failed login and applies [lockUntil] when a lockout is warranted. */
    suspend fun recordFailedLogin(
        userId: Int,
        lockUntil: Instant?,
    )

    /** Clears the failed-login counter and any active lockout for the account. */
    suspend fun resetFailedLogins(userId: Int)

    /** Revokes every refresh token belonging to [familyId]. */
    suspend fun revokeTokenFamily(familyId: String)

    /** Revokes all refresh tokens belonging to [userId]. */
    suspend fun revokeAllTokensForUser(userId: Int)

    /** Revokes one refresh token without affecting its other family members. */
    suspend fun revokeSpecificRefreshToken(
        userId: Int,
        token: String,
    )

    /** Creates an email-verification token that expires at [expiresAt]. */
    suspend fun createVerificationToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    )

    /** Finds the record associated with a verification token. */
    suspend fun findVerificationToken(token: String): VerificationTokenRecord?

    /** Marks a verification token as used and reports whether it was updated. */
    suspend fun markTokenUsed(tokenId: Int): Boolean

    /** Marks the account's email address as verified. */
    suspend fun markEmailVerified(userId: Int)

    /** Invalidates all outstanding email-verification tokens for the account. */
    suspend fun invalidateVerificationTokens(userId: Int)

    /** Creates a password-reset token that expires at [expiresAt]. */
    suspend fun createPasswordResetToken(
        userId: Int,
        token: String,
        expiresAt: Instant,
    )

    /** Finds the record associated with a password-reset token. */
    suspend fun findPasswordResetToken(token: String): PasswordResetToken?

    /** Marks a password-reset token as used at [usedAt]. */
    suspend fun markPasswordResetTokenUsed(
        tokenId: Int,
        usedAt: Instant,
    ): Boolean

    /** Invalidates all outstanding password-reset tokens for the account. */
    suspend fun invalidatePasswordResetTokens(userId: Int)

    /** Replaces the account's stored password hash. */
    suspend fun updatePassword(
        userId: Int,
        newPasswordHash: String,
    )

    /** Replaces the account's contact details and reports whether the account exists. */
    suspend fun updateContactInfo(
        userId: Int,
        contactInfo: UserContactInfo,
    ): Boolean
}
