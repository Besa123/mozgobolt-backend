package com.shelflife.feature.user.service

import com.shelflife.feature.user.domain.model.RefreshError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.fail

class UserServiceLogoutTest {
    @Test
    fun `logging out revokes only the presented refresh token`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val signIn =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })

            harness.service.logoutUser(signIn.refreshToken)

            val result = harness.service.refreshToken(signIn.refreshToken)
            assertError(RefreshError.TOKEN_REUSE_DETECTED, result)
        }
    }

    @Test
    fun `logging out with an unknown refresh token does not throw`() {
        runBlocking {
            val harness = newHarness()

            harness.service.logoutUser("some-refresh-token-that-was-never-issued")
        }
    }

    @Test
    fun `logging out of all sessions revokes every refresh token for that user`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val sessionA =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            val sessionB =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })

            harness.service.logoutAllSessions(user.id)

            assertError(RefreshError.TOKEN_REUSE_DETECTED, harness.service.refreshToken(sessionA.refreshToken))
            assertError(RefreshError.TOKEN_REUSE_DETECTED, harness.service.refreshToken(sessionB.refreshToken))
        }
    }
}
