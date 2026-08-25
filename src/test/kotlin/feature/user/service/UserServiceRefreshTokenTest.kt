package com.besa.shelflife.feature.user.service

import com.besa.shelflife.feature.user.domain.model.RefreshError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.fail

class UserServiceRefreshTokenTest {
    @Test
    fun `refreshing rotates the token and revokes the old one`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val signIn =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })

            val refreshed =
                harness.service
                    .refreshToken(signIn.refreshToken)
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })

            assertNotEquals(signIn.refreshToken, refreshed.refreshToken)
        }
    }

    @Test
    fun `reusing an already-rotated refresh token is detected and revokes the whole family`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val signIn =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            val rotated =
                harness.service
                    .refreshToken(signIn.refreshToken)
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })

            val reuseResult = harness.service.refreshToken(signIn.refreshToken)
            assertError(RefreshError.TOKEN_REUSE_DETECTED, reuseResult)

            val secondUseOfRotated = harness.service.refreshToken(rotated.refreshToken)
            assertError(RefreshError.TOKEN_REUSE_DETECTED, secondUseOfRotated)
        }
    }

    @Test
    fun `two separate logins are independent token families and revoking one does not affect the other`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val firstSession =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            val secondSession =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })

            harness.service.refreshToken(firstSession.refreshToken)
            val reuseOfFirstSession = harness.service.refreshToken(firstSession.refreshToken)
            assertError(RefreshError.TOKEN_REUSE_DETECTED, reuseOfFirstSession)

            val secondSessionRefreshed = harness.service.refreshToken(secondSession.refreshToken)
            secondSessionRefreshed.fold(onSuccess = {}, onError = { fail("unrelated session was revoked too: $it") })
        }
    }

    @Test
    fun `refreshing with garbage input fails without crashing`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.refreshToken("not-a-real-token")

            assertError(RefreshError.INVALID_CREDENTIALS, result)
        }
    }

    @Test
    fun `refreshing with a token for a user that no longer exists fails cleanly`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.refreshToken(harness.tokenManager.generateRefreshToken(userId = 999))

            assertError(RefreshError.INVALID_CREDENTIALS, result)
        }
    }
}
