/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UserServiceVerifyEmailTest {
    @Test
    fun `verifying with the token sent at registration succeeds`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(
                password = "Str0ngPassphrase1",
                email = "user@example.com",
                name = "User",
                role = UserRole.BUYER,
            )
            val token =
                harness.emailOutbox.verificationEmails
                    .single()
                    .rawToken

            val result = harness.service.verifyEmail(token)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val user = assertNotNull(harness.repository.findUser("user@example.com"))
            assertTrue(user.isEmailVerified)
        }
    }

    @Test
    fun `verifying with an unknown token is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.verifyEmail("unknown-token")

            assertError(VerifyEmailError.INVALID_TOKEN, result)
        }
    }

    @Test
    fun `verifying with an expired token is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("expired-token"),
                Instant.now().minusSeconds(60),
            )

            val result = harness.service.verifyEmail("expired-token")

            assertError(VerifyEmailError.EXPIRED_TOKEN, result)
        }
    }

    @Test
    fun `verifying a token that expired one second ago is rejected`() {
        // Boundary case: `isBefore(now)` means expiry is exclusive of "right now".
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("just-expired"),
                Instant.now().minusSeconds(1),
            )

            val result = harness.service.verifyEmail("just-expired")

            assertError(VerifyEmailError.EXPIRED_TOKEN, result)
        }
    }

    @Test
    fun `verifying an already-verified account is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = true,
                )
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("some-token"),
                Instant.now().plusSeconds(3600),
            )

            val result = harness.service.verifyEmail("some-token")

            assertError(VerifyEmailError.ALREADY_VERIFIED, result)
        }
    }

    @Test
    fun `verifying the same token twice fails the second time`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(
                password = "Str0ngPassphrase1",
                email = "user@example.com",
                name = "User",
                role = UserRole.BUYER,
            )
            val token =
                harness.emailOutbox.verificationEmails
                    .single()
                    .rawToken
            harness.service
                .verifyEmail(token)
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })

            val secondAttempt = harness.service.verifyEmail(token)

            // The record's `used` flag is checked before the already-verified check, so a replay
            // of an already-consumed token deterministically reports INVALID_TOKEN, never
            // ALREADY_VERIFIED — see UserServiceI.verifyEmail.
            assertError(VerifyEmailError.INVALID_TOKEN, secondAttempt)
        }
    }

    @Test
    fun `a failed conditional token claim does not mark the account verified`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            val tokenHash = harness.tokenManager.hashTokenForStorage("raced-token")
            harness.repository.createVerificationToken(user.id, tokenHash, Instant.now().plusSeconds(3600))
            harness.repository.failNextVerificationTokenClaim = true

            val result = harness.service.verifyEmail("raced-token")

            assertError(VerifyEmailError.INVALID_TOKEN, result)
            assertFalse(assertNotNull(harness.repository.findUserById(user.id)).isEmailVerified)
            assertFalse(assertNotNull(harness.repository.findVerificationToken(tokenHash)).used)
        }
    }

    @Test
    fun `verification rechecks token existence after acquiring the user lock`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser("user@example.com", "Str0ngPassphrase1", verified = false)
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("removed-after-read"),
                Instant.now().plusSeconds(3600),
            )
            harness.repository.removeVerificationTokenOnSecondLookup = true

            assertError(VerifyEmailError.INVALID_TOKEN, harness.service.verifyEmail("removed-after-read"))
        }
    }

    @Test
    fun `verification rejects a token that expires while waiting for the user lock`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser("user@example.com", "Str0ngPassphrase1", verified = false)
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("expired-after-read"),
                Instant.now().plusSeconds(3600),
            )
            harness.repository.expireVerificationTokenOnSecondLookup = true

            assertError(VerifyEmailError.EXPIRED_TOKEN, harness.service.verifyEmail("expired-after-read"))
        }
    }

    @Test
    fun `verification fails if the user disappears after the token lookup`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("orphaned-during-verify"),
                Instant.now().plusSeconds(3600),
            )
            harness.repository.returnNullOnNextLockedUserRead = true

            assertError(VerifyEmailError.INVALID_TOKEN, harness.service.verifyEmail("orphaned-during-verify"))
        }
    }

    @Test
    fun `verification fails if its token is removed while waiting for the user lock`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("removed-during-verify"),
                Instant.now().plusSeconds(3600),
            )
            harness.repository.removeVerificationTokenOnSecondLookup = true

            assertError(VerifyEmailError.INVALID_TOKEN, harness.service.verifyEmail("removed-during-verify"))
        }
    }

    @Test
    fun `verification fails if the token expires while waiting for the user lock`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            harness.repository.createVerificationToken(
                user.id,
                harness.tokenManager.hashTokenForStorage("expired-during-verify"),
                Instant.now().plusSeconds(3600),
            )
            harness.repository.expireVerificationTokenOnSecondLookup = true

            assertError(VerifyEmailError.EXPIRED_TOKEN, harness.service.verifyEmail("expired-during-verify"))
        }
    }
}
