package com.shelflife.feature.user.service

import com.shelflife.feature.user.domain.model.VerifyEmailError
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UserServiceVerifyEmailTest {
    @Test
    fun `verifying with the token sent at registration succeeds`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(password = "Str0ngPass", email = "user@example.com", name = "User")
            val token =
                harness.emailService.sentTokens
                    .single()
                    .second

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
                    password = "Str0ngPass",
                    verified = false,
                )
            harness.repository.createVerificationToken(user.id, "expired-token", Instant.now().minusSeconds(60))

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
                    password = "Str0ngPass",
                    verified = false,
                )
            harness.repository.createVerificationToken(user.id, "just-expired", Instant.now().minusSeconds(1))

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
                    password = "Str0ngPass",
                    verified = true,
                )
            harness.repository.createVerificationToken(user.id, "some-token", Instant.now().plusSeconds(3600))

            val result = harness.service.verifyEmail("some-token")

            assertError(VerifyEmailError.ALREADY_VERIFIED, result)
        }
    }

    @Test
    fun `verifying the same token twice fails the second time`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(password = "Str0ngPass", email = "user@example.com", name = "User")
            val token =
                harness.emailService.sentTokens
                    .single()
                    .second
            harness.service
                .verifyEmail(token)
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })

            val secondAttempt = harness.service.verifyEmail(token)

            // The account is now verified, so the token is both "used" and the account is
            // already verified; either error would be acceptable here, but it must not succeed.
            secondAttempt.fold(
                onSuccess = { fail("a verification token must not be usable twice") },
                onError = {},
            )
        }
    }
}
