package com.besa.shelflife.feature.user.service

import com.besa.shelflife.feature.user.domain.model.VerifyEmailError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class UserServiceResendVerificationTest {
    @Test
    fun `resending verification for an unknown user id fails cleanly`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.resendVerificationEmail(userId = 404)

            assertError(VerifyEmailError.INVALID_TOKEN, result)
        }
    }

    @Test
    fun `resending verification for an already-verified account is rejected`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPass",
                    verified = true,
                )

            val result = harness.service.resendVerificationEmail(user.id)

            assertError(VerifyEmailError.ALREADY_VERIFIED, result)
        }
    }

    @Test
    fun `resending verification invalidates the original token and issues a working new one`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(password = "Str0ngPass", email = "user@example.com", name = "User")
            val originalToken =
                harness.emailService.sentTokens
                    .single()
                    .second
            val user = assertNotNull(harness.repository.findUser("user@example.com"))

            harness.service
                .resendVerificationEmail(user.id)
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val newToken =
                harness.emailService.sentTokens
                    .last()
                    .second

            assertNotEquals(originalToken, newToken)
            assertError(VerifyEmailError.INVALID_TOKEN, harness.service.verifyEmail(originalToken))
            harness.service
                .verifyEmail(newToken)
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `cancellation while resending the verification email propagates instead of being swallowed`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPass",
                    verified = false,
                )
            harness.emailService.failureToThrow = CancellationException("client disconnected")

            assertFailsWith<CancellationException> {
                harness.service.resendVerificationEmail(user.id)
            }
        }
    }
}
