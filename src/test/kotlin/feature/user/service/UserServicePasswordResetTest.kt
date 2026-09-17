package com.shelflife.feature.user.service

import com.shelflife.feature.user.domain.model.PasswordResetError
import com.shelflife.feature.user.domain.model.TokenValidationResult
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private suspend fun Harness.requestPasswordResetToken(email: String): String {
    service.requestPasswordReset(email)
    return emailService.sentPasswordResetTokens.last().second
}

class UserServicePasswordResetTest {
    // --- requestPasswordReset ---------------------------------------------------------------

    @Test
    fun `requesting a reset for an unknown email returns success without sending an email`() {
        runBlocking {
            val harness = newHarness()

            harness.service.requestPasswordReset("nobody@example.com")

            assertTrue(harness.emailService.sentPasswordResetTokens.isEmpty())
        }
    }

    @Test
    fun `requesting a reset for a locked account returns success without sending an email`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass1")
            harness.repository.recordFailedLogin(user.id, lockUntil = Instant.now().plusSeconds(3600))

            harness.service.requestPasswordReset("user@example.com")

            assertTrue(harness.emailService.sentPasswordResetTokens.isEmpty())
        }
    }

    @Test
    fun `requesting a reset for a known, unlocked account sends an email and returns a working token`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass1")

            val token = harness.requestPasswordResetToken("user@example.com")

            val (to, sentToken) = harness.emailService.sentPasswordResetTokens.single()
            assertEquals("user@example.com", to)
            assertEquals(token, sentToken)
            harness.service
                .validatePasswordResetToken(token)
                .fold(onSuccess = {}, onError = { fail("token from the request should validate but got $it") })
        }
    }

    @Test
    fun `requesting a reset twice invalidates the first token`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass1")

            val firstToken = harness.requestPasswordResetToken("user@example.com")
            val secondToken = harness.requestPasswordResetToken("user@example.com")

            assertNotEquals(firstToken, secondToken)
            assertError(PasswordResetError.INVALID_TOKEN, harness.service.validatePasswordResetToken(firstToken))
            harness.service
                .validatePasswordResetToken(secondToken)
                .fold(onSuccess = {}, onError = { fail("newest token should still be valid but got $it") })
        }
    }

    // --- validatePasswordResetToken ----------------------------------------------------------

    @Test
    fun `validating a token issued by a reset request succeeds and returns the account email`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass1")
            val token = harness.requestPasswordResetToken("user@example.com")

            val result = harness.service.validatePasswordResetToken(token)

            assertEquals(
                "user@example.com",
                result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") }),
            )
        }
    }

    @Test
    fun `validating an unknown token is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.validatePasswordResetToken("unknown-token")

            assertError(PasswordResetError.INVALID_TOKEN, result)
        }
    }

    @Test
    fun `validating an expired token is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass1")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("expired-token"),
                Instant.now().minusSeconds(60),
            )

            val result = harness.service.validatePasswordResetToken("expired-token")

            assertError(PasswordResetError.EXPIRED_TOKEN, result)
        }
    }

    @Test
    fun `validating a token that expired one second ago is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass1")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("just-expired"),
                Instant.now().minusSeconds(1),
            )

            val result = harness.service.validatePasswordResetToken("just-expired")

            assertError(PasswordResetError.EXPIRED_TOKEN, result)
        }
    }

    @Test
    fun `validating an already-used token is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass1")
            val hashedUsedToken = harness.tokenManager.hashTokenForStorage("used-token")
            harness.repository.createPasswordResetToken(user.id, hashedUsedToken, Instant.now().plusSeconds(3600))
            harness.repository.markPasswordResetTokenUsed(
                harness.repository.findPasswordResetToken(hashedUsedToken)!!.id,
                Instant.now(),
            )

            val result = harness.service.validatePasswordResetToken("used-token")

            assertError(PasswordResetError.INVALID_TOKEN, result)
        }
    }

    // --- confirmPasswordReset -----------------------------------------------------------------

    @Test
    fun `confirming with a valid token updates the password and revokes all sessions`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "OldPass1x")
            harness.repository.saveRefreshToken(user.id, "some-refresh-token", familyId = "family-1")
            val token = harness.requestPasswordResetToken("user@example.com")

            val result = harness.service.confirmPasswordReset(token, "NewPass1x")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val updatedUser = assertNotNull(harness.repository.findUserById(user.id))
            assertEquals("hashed:NewPass1x", updatedUser.passwordHash)
            val revoked = harness.repository.validateAndRevokeRefreshToken(user.id, "some-refresh-token")
            assertTrue(
                revoked is TokenValidationResult.AlreadyRevoked,
                "all sessions must be revoked after a password reset, but token state was $revoked",
            )
        }
    }

    @Test
    fun `validating a token whose user no longer exists is rejected as user-not-found`() {
        runBlocking {
            val harness = newHarness()
            // No user is seeded for id 999 — a valid, unexpired, unused token can still exist for
            // an id that no longer resolves to a user (e.g. deleted between issuance and use).
            harness.repository.createPasswordResetToken(
                999,
                harness.tokenManager.hashTokenForStorage("orphaned-token"),
                Instant.now().plusSeconds(3600),
            )

            val result = harness.service.validatePasswordResetToken("orphaned-token")

            assertError(PasswordResetError.USER_NOT_FOUND, result)
        }
    }

    @Test
    fun `confirming with an unknown token is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.confirmPasswordReset("unknown-token", "NewPass1x")

            assertError(PasswordResetError.INVALID_TOKEN, result)
        }
    }

    @Test
    fun `confirming with an expired token is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "OldPass1x")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("expired-token"),
                Instant.now().minusSeconds(60),
            )

            val result = harness.service.confirmPasswordReset("expired-token", "NewPass1x")

            assertError(PasswordResetError.EXPIRED_TOKEN, result)
        }
    }

    @Test
    fun `confirming with a weak new password is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "OldPass1x")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("some-token"),
                Instant.now().plusSeconds(3600),
            )

            val result = harness.service.confirmPasswordReset("some-token", "weak")

            assertError(PasswordResetError.WEAK_PASSWORD, result)
        }
    }

    @Test
    fun `confirming with the same password as the current one is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "SamePass1x")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("some-token"),
                Instant.now().plusSeconds(3600),
            )

            val result = harness.service.confirmPasswordReset("some-token", "SamePass1x")

            assertError(PasswordResetError.SAME_AS_OLD, result)
        }
    }

    @Test
    fun `confirming for a locked account is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "OldPass1x")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("some-token"),
                Instant.now().plusSeconds(3600),
            )
            harness.repository.recordFailedLogin(user.id, lockUntil = Instant.now().plusSeconds(3600))

            val result = harness.service.confirmPasswordReset("some-token", "NewPass1x")

            assertError(PasswordResetError.ACCOUNT_LOCKED, result)
        }
    }

    @Test
    fun `confirming with a token whose user no longer exists is rejected as user-not-found`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.createPasswordResetToken(
                999,
                harness.tokenManager.hashTokenForStorage("orphaned-token"),
                Instant.now().plusSeconds(3600),
            )

            val result = harness.service.confirmPasswordReset("orphaned-token", "NewPass1x")

            assertError(PasswordResetError.USER_NOT_FOUND, result)
        }
    }

    @Test
    fun `confirming the same token twice fails the second time`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "OldPass1x")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("some-token"),
                Instant.now().plusSeconds(3600),
            )

            harness.service
                .confirmPasswordReset("some-token", "NewPass1x")
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val secondAttempt = harness.service.confirmPasswordReset("some-token", "AnotherPass1x")

            assertError(PasswordResetError.INVALID_TOKEN, secondAttempt)
            val updatedUser = assertNotNull(harness.repository.findUserById(user.id))
            assertEquals(
                "hashed:NewPass1x",
                updatedUser.passwordHash,
                "the second, rejected attempt must not overwrite the password set by the first",
            )
        }
    }

    @Test
    fun `a successful reset invalidates other outstanding reset tokens for the same user`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "OldPass1x")
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("token-a"),
                Instant.now().plusSeconds(3600),
            )
            harness.repository.createPasswordResetToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("token-b"),
                Instant.now().plusSeconds(3600),
            )

            harness.service
                .confirmPasswordReset("token-a", "NewPass1x")
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })

            assertError(
                PasswordResetError.INVALID_TOKEN,
                harness.service.validatePasswordResetToken("token-b"),
            )
        }
    }
}
