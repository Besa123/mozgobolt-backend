package com.besa.shelflife.feature.user.service

import com.besa.shelflife.feature.user.domain.model.LoginError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UserServiceSignInTest {
    @Test
    fun `signing in with correct credentials succeeds and issues tokens`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")

            val result = harness.service.signInUser(password = "Str0ngPass", email = "user@example.com")

            val response = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertTrue(response.accessToken.isNotBlank())
            assertTrue(response.refreshToken.isNotBlank())
        }
    }

    @Test
    fun `signing in with a different case and surrounding whitespace on the email still succeeds`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")

            val result = harness.service.signInUser(password = "Str0ngPass", email = "  User@Example.COM  ")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `signing in with an unknown email fails without revealing that`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.signInUser(password = "Str0ngPass", email = "nobody@example.com")

            assertError(LoginError.INVALID_CREDENTIALS, result)
        }
    }

    @Test
    fun `signing in with an unknown email still runs a password check to avoid a timing oracle`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "real-user@example.com", password = "Str0ngPass")

            harness.service.signInUser(password = "irrelevant-password", email = "unknown@example.com")
            val callsForUnknownUser = harness.passwordService.verifyCallCount

            harness.service.signInUser(password = "wrong-password", email = "real-user@example.com")
            val callsForKnownUser = harness.passwordService.verifyCallCount - callsForUnknownUser

            assertEquals(
                callsForKnownUser,
                callsForUnknownUser,
                "expected the unknown-account path to perform the same number of password checks " +
                    "as the known-account-wrong-password path",
            )
        }
    }

    @Test
    fun `signing in with the wrong password records a failed attempt`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")

            val result = harness.service.signInUser(password = "wrong-password", email = "user@example.com")

            assertError(LoginError.INVALID_CREDENTIALS, result)
            assertEquals(1, assertNotNull(harness.repository.findUserById(user.id)).failedLoginAttempts)
        }
    }

    @Test
    fun `enough failed attempts locks the account`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")

            repeat(5) {
                harness.service.signInUser(password = "wrong-password", email = "user@example.com")
            }
            val result = harness.service.signInUser(password = "Str0ngPass", email = "user@example.com")

            assertError(LoginError.ACCOUNT_LOCKED, result)
            assertTrue(assertNotNull(harness.repository.findUserById(user.id)).isLocked)
        }
    }

    @Test
    fun `a locked account rejects even the correct password`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            repeat(5) {
                harness.service.signInUser(password = "wrong-password", email = "user@example.com")
            }

            val result = harness.service.signInUser(password = "Str0ngPass", email = "user@example.com")

            assertError(LoginError.ACCOUNT_LOCKED, result)
        }
    }

    @Test
    fun `a successful login resets the failed attempt counter`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            harness.service.signInUser(password = "wrong-password", email = "user@example.com")

            harness.service.signInUser(password = "Str0ngPass", email = "user@example.com")

            assertEquals(0, assertNotNull(harness.repository.findUserById(user.id)).failedLoginAttempts)
        }
    }
}
