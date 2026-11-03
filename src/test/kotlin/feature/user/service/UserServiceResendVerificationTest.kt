/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
                    password = "Str0ngPassphrase1",
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
            harness.service.createUser(
                password = "Str0ngPassphrase1",
                email = "user@example.com",
                name = "User",
                role = UserRole.BUYER,
            )
            val originalToken =
                harness.emailOutbox.verificationEmails
                    .single()
                    .rawToken
            val user = assertNotNull(harness.repository.findUser("user@example.com"))

            harness.service
                .resendVerificationEmail(user.id)
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val newToken =
                harness.emailOutbox.verificationEmails
                    .last()
                    .rawToken

            assertNotEquals(originalToken, newToken)
            assertError(VerifyEmailError.INVALID_TOKEN, harness.service.verifyEmail(originalToken))
            harness.service
                .verifyEmail(newToken)
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `cancellation while queuing a verification resend propagates`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            harness.emailOutbox.failureToThrow = CancellationException("client disconnected")

            assertFailsWith<CancellationException> {
                harness.service.resendVerificationEmail(user.id)
            }
        }
    }

    @Test
    fun `a resend queues the replacement token for asynchronous delivery`() {
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "user@example.com",
                    password = "Str0ngPassphrase1",
                    verified = false,
                )
            val result = harness.service.resendVerificationEmail(user.id)

            result.fold(onSuccess = {}, onError = { fail("resend should remain successful: $it") })
            assertTrue(harness.repository.hasVerificationToken(user.id))
            assertEquals(
                user.id,
                harness.emailOutbox.pending
                    .single()
                    .userId,
            )
        }
    }
}
