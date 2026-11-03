/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.user.data.database.RefreshTokensTable
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UserRepositoryRealDatabaseTest {
    @Test
    fun `expired token records are purged after their retention boundary`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val now = Instant.parse("2026-01-31T00:00:00Z")
            val oneTimeTokenCutoff = now.minusSeconds(30 * 24 * 60 * 60)

            tx.transactional {
                repository.createVerificationToken(1, "old-verification", oneTimeTokenCutoff.minusSeconds(1))
                repository.createVerificationToken(1, "retained-verification", oneTimeTokenCutoff.plusSeconds(1))
                repository.createPasswordResetToken(1, "old-password-reset", oneTimeTokenCutoff.minusSeconds(1))
                repository.createPasswordResetToken(1, "retained-password-reset", oneTimeTokenCutoff.plusSeconds(1))
                repository.saveRefreshToken(
                    userId = 1,
                    token = "expired-family-token",
                    familyId = "expired-family",
                    familyExpiresAt = now.minusSeconds(1),
                )
                repository.saveRefreshToken(
                    userId = 1,
                    token = "active-family-token",
                    familyId = "active-family",
                    familyExpiresAt = now.plusSeconds(60),
                )
            }

            val deleted =
                tx.transactional {
                    repository.purgeExpiredAuthenticationTokens(now, oneTimeTokenCutoff)
                }

            assertEquals(3, deleted)
            assertNull(tx.transactional { repository.findVerificationToken("old-verification") })
            assertNotNull(tx.transactional { repository.findVerificationToken("retained-verification") })
            assertNull(tx.transactional { repository.findPasswordResetToken("old-password-reset") })
            assertNotNull(tx.transactional { repository.findPasswordResetToken("retained-password-reset") })
            assertEquals(1L, tx.transactional { RefreshTokensTable.selectAll().count() })
        }
    }

    @Test
    fun `contact update reports a missing user without changing another account`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val before = assertNotNull(tx.transactional { repository.findUserById(1) })
            val contactInfo =
                UserContactInfo(
                    phoneNumber = "+3612345678",
                    phoneNumberVisible = true,
                    whatsappNumber = null,
                    whatsappVisible = false,
                    viberNumber = null,
                    viberVisible = false,
                    messengerUsername = null,
                    messengerVisible = false,
                )

            assertFalse(tx.transactional { repository.updateContactInfo(userId = -1, contactInfo = contactInfo) })
            assertEquals(before, tx.transactional { repository.findUserById(1) })
        }
    }

    @Test
    fun `user creation atomically ignores a duplicate email and keeps the original row`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val first =
                tx.transactional {
                    repository.createUser(
                        email = "duplicate@example.com",
                        password = "first-hash",
                        name = "First",
                        role = UserRole.BUYER,
                    )
                }
            val duplicate =
                tx.transactional {
                    repository.createUser(
                        email = "duplicate@example.com",
                        password = "second-hash",
                        name = "Second",
                        role = UserRole.VENDOR,
                    )
                }

            assertNotNull(first)
            assertNull(duplicate)
            assertEquals("First", tx.transactional { repository.findUser("duplicate@example.com")?.name })
        }
    }

    @Test
    fun `user lookups distinguish missing rows and creation preserves optional profile data`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()

            tx.transactional {
                assertEquals("test-user-1@example.com", repository.findUser("test-user-1@example.com")?.email)
                assertEquals(1, repository.findUserById(1)?.id)
                assertNull(repository.findUser("missing@example.com"))
                assertNull(repository.findUserById(-1))

                val created =
                    assertNotNull(
                        repository.createUser(
                            email = "vendor@example.com",
                            password = "hashed-password",
                            name = "Vendor",
                            role = UserRole.VENDOR,
                            phoneNumber = "+3612345678",
                            whatsappNumber = "+3612345679",
                            viberNumber = "+3612345680",
                            messengerUsername = "vendor.chat",
                        ),
                        "the first insert for an unused email must succeed",
                    )

                assertEquals("vendor@example.com", created.email)
                assertEquals("hashed-password", created.passwordHash)
                assertEquals(UserRole.VENDOR, created.role)
                assertEquals("+3612345678", created.phoneNumber)
                assertEquals("+3612345679", created.whatsappNumber)
                assertEquals("+3612345680", created.viberNumber)
                assertEquals("vendor.chat", created.messengerUsername)
                assertEquals(created, repository.findUserById(created.id))
            }
        }
    }

    @Test
    fun `refresh token validation distinguishes missing expired valid and reused tokens`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val expiredRepository = UserRepositoryI(refreshTokenExpiration = -1.seconds)

            tx.transactional {
                val familyExpiresAt =
                    repository.saveRefreshToken(
                        userId = 1,
                        token = "fresh-token",
                        familyId = "fresh-family",
                    )

                assertTrue(repository.isTokenFamilyActive(1, "fresh-family", Instant.now()))
                assertEquals(
                    TokenValidationResult.Valid("fresh-family", familyExpiresAt),
                    repository.validateAndRevokeRefreshToken(userId = 1, token = "fresh-token"),
                )
                assertFalse(repository.isTokenFamilyActive(1, "fresh-family", Instant.now()))
                assertEquals(
                    TokenValidationResult.AlreadyRevoked("fresh-family"),
                    repository.validateAndRevokeRefreshToken(userId = 1, token = "fresh-token"),
                )
                assertEquals(
                    TokenValidationResult.NotFound,
                    repository.validateAndRevokeRefreshToken(userId = 2, token = "fresh-token"),
                )
                assertEquals(
                    TokenValidationResult.NotFound,
                    repository.validateAndRevokeRefreshToken(userId = 1, token = "missing-token"),
                )

                expiredRepository.saveRefreshToken(
                    userId = 1,
                    token = "expired-token",
                    familyId = "expired-family",
                )
                assertEquals(
                    TokenValidationResult.NotFound,
                    expiredRepository.validateAndRevokeRefreshToken(userId = 1, token = "expired-token"),
                )
            }
        }
    }

    @Test
    fun `refresh token revocation is scoped by family user and token`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()

            tx.transactional {
                repository.saveRefreshToken(userId = 1, token = "family-user-one", familyId = "shared-family")
                repository.saveRefreshToken(userId = 2, token = "family-user-two", familyId = "shared-family")
                repository.saveRefreshToken(userId = 1, token = "specific-token", familyId = "specific-family")
                repository.saveRefreshToken(userId = 1, token = "user-token", familyId = "user-family")
                val otherFamilyExpiresAt =
                    repository.saveRefreshToken(
                        userId = 2,
                        token = "unrelated-user-token",
                        familyId = "other-family",
                    )

                repository.revokeTokenFamily("shared-family")
                repository.revokeSpecificRefreshToken(userId = 1, token = "specific-token")
                repository.revokeAllTokensForUser(userId = 1)

                assertEquals(
                    TokenValidationResult.AlreadyRevoked("shared-family"),
                    repository.validateAndRevokeRefreshToken(userId = 1, token = "family-user-one"),
                )
                assertEquals(
                    TokenValidationResult.AlreadyRevoked("shared-family"),
                    repository.validateAndRevokeRefreshToken(userId = 2, token = "family-user-two"),
                )
                assertEquals(
                    TokenValidationResult.AlreadyRevoked("specific-family"),
                    repository.validateAndRevokeRefreshToken(userId = 1, token = "specific-token"),
                )
                assertEquals(
                    TokenValidationResult.AlreadyRevoked("user-family"),
                    repository.validateAndRevokeRefreshToken(userId = 1, token = "user-token"),
                )
                assertEquals(
                    TokenValidationResult.Valid("other-family", otherFamilyExpiresAt),
                    repository.validateAndRevokeRefreshToken(userId = 2, token = "unrelated-user-token"),
                )
            }
        }
    }

    @Test
    fun `concurrent verification and reset token claims each have exactly one winner`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val (verificationId, resetId) =
                tx.transactional {
                    repository.createVerificationToken(1, "verification-claim-race", Instant.now().plusSeconds(3600))
                    repository.createPasswordResetToken(1, "reset-claim-race", Instant.now().plusSeconds(3600))
                    repository.findVerificationToken("verification-claim-race")!!.id to
                        repository.findPasswordResetToken("reset-claim-race")!!.id
                }

            val verificationResults =
                concurrentClaims {
                    tx.transactional { repository.markTokenUsed(verificationId) }
                }
            val resetResults =
                concurrentClaims {
                    tx.transactional { repository.markPasswordResetTokenUsed(resetId, Instant.now()) }
                }

            assertEquals(1, verificationResults.count { it })
            assertEquals(1, verificationResults.count { !it })
            assertEquals(1, resetResults.count { it })
            assertEquals(1, resetResults.count { !it })
        }
    }

    @Test
    fun `failed login updates increment attempts and reset both lock fields`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val lockUntil = Instant.parse("2030-01-01T00:00:00Z")

            tx.transactional { repository.recordFailedLogin(userId = 1, lockUntil = lockUntil) }
            assertEquals(lockUntil, tx.transactional { repository.findUserById(1)?.lockedUntil })

            tx.transactional { repository.recordFailedLogin(userId = 1, lockUntil = null) }
            val failed = assertNotNull(tx.transactional { repository.findUserById(1) })
            assertEquals(2, failed.failedLoginAttempts)
            assertNull(failed.lockedUntil)

            tx.transactional { repository.resetFailedLogins(userId = 1) }
            val reset = assertNotNull(tx.transactional { repository.findUserById(1) })
            assertEquals(0, reset.failedLoginAttempts)
            assertNull(reset.lockedUntil)
        }
    }

    @Test
    fun `verification tokens can be found consumed invalidated and linked to email verification`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val expiresAt = Instant.parse("2030-01-01T00:00:00Z")

            tx.transactional {
                repository.createVerificationToken(userId = 1, token = "verify-one", expiresAt = expiresAt)
                repository.createVerificationToken(userId = 1, token = "verify-two", expiresAt = expiresAt)

                val token = assertNotNull(repository.findVerificationToken("verify-one"))
                assertEquals(1, token.userId)
                assertEquals(expiresAt, token.expiresAt)
                assertFalse(token.used)
                assertNull(repository.findVerificationToken("missing-verification-token"))

                assertTrue(repository.markTokenUsed(token.id))
                assertFalse(repository.markTokenUsed(token.id))
                assertFalse(repository.markTokenUsed(-1))
            }

            tx.transactional { repository.invalidateVerificationTokens(userId = 1) }
            assertTrue(assertNotNull(tx.transactional { repository.findVerificationToken("verify-two") }).used)

            tx.transactional { repository.markEmailVerified(userId = 1) }
            assertTrue(assertNotNull(tx.transactional { repository.findUserById(1) }).isEmailVerified)
        }
    }

    @Test
    fun `password reset token lifecycle and account updates persist nulls and visibility flags`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = UserRepositoryI()
            val expiresAt = Instant.parse("2030-01-01T00:00:00Z")
            val usedAt = Instant.parse("2026-01-02T03:04:05Z")
            val contactInfo =
                UserContactInfo(
                    phoneNumber = "+3612345678",
                    phoneNumberVisible = true,
                    whatsappNumber = "+3612345679",
                    whatsappVisible = false,
                    viberNumber = null,
                    viberVisible = false,
                    messengerUsername = "vendor.chat",
                    messengerVisible = true,
                )

            val tokenId =
                tx.transactional {
                    repository.createPasswordResetToken(userId = 1, token = "reset-one", expiresAt = expiresAt)
                    repository.createPasswordResetToken(userId = 1, token = "reset-two", expiresAt = expiresAt)

                    val token = assertNotNull(repository.findPasswordResetToken("reset-one"))
                    assertEquals(1, token.userId)
                    assertEquals(expiresAt, token.expiresAt)
                    assertFalse(token.used)
                    assertNull(token.usedAt)
                    assertTrue(token.createdAt <= Instant.now())
                    assertNull(repository.findPasswordResetToken("missing-reset-token"))

                    token.id
                }

            assertTrue(tx.transactional { repository.markPasswordResetTokenUsed(tokenId, usedAt) })
            assertFalse(tx.transactional { repository.markPasswordResetTokenUsed(tokenId, usedAt.plusSeconds(1)) })
            assertFalse(tx.transactional { repository.markPasswordResetTokenUsed(-1, usedAt) })
            assertEquals(usedAt, tx.transactional { repository.findPasswordResetToken("reset-one")?.usedAt })

            tx.transactional { repository.invalidatePasswordResetTokens(userId = 1) }
            val invalidated = assertNotNull(tx.transactional { repository.findPasswordResetToken("reset-two") })
            assertTrue(invalidated.used)
            assertNull(invalidated.usedAt)

            tx.transactional {
                repository.updatePassword(userId = 1, newPasswordHash = "new-hashed-password")
                repository.updateContactInfo(userId = 1, contactInfo = contactInfo)
            }

            val user = assertNotNull(tx.transactional { repository.findUserById(1) })
            assertEquals("new-hashed-password", user.passwordHash)
            assertEquals(contactInfo.phoneNumber, user.phoneNumber)
            assertEquals(contactInfo.phoneNumberVisible, user.phoneNumberVisible)
            assertEquals(contactInfo.whatsappNumber, user.whatsappNumber)
            assertEquals(contactInfo.whatsappVisible, user.whatsappVisible)
            assertEquals(contactInfo.viberNumber, user.viberNumber)
            assertEquals(contactInfo.viberVisible, user.viberVisible)
            assertEquals(contactInfo.messengerUsername, user.messengerUsername)
            assertEquals(contactInfo.messengerVisible, user.messengerVisible)
        }
    }

    private suspend fun concurrentClaims(action: suspend () -> Boolean): List<Boolean> {
        val start = CyclicBarrier(2)
        return coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    start.await(10, TimeUnit.SECONDS)
                    action()
                },
                async(Dispatchers.IO) {
                    start.await(10, TimeUnit.SECONDS)
                    action()
                },
            ).map { it.await() }
        }
    }
}
