package com.besa.shelflife.feature.user.service

import com.besa.shelflife.feature.user.domain.model.RegisterError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.fail

class UserServiceCreateUserTest {
    @Test
    fun `registering a new user sends a verification email`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(password = "Str0ngPass", email = "New.User@Example.com", name = "New User")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val user = assertNotNull(harness.repository.findUser("new.user@example.com"))
            val (recipient, token) = harness.emailService.sentTokens.single()
            assertEquals(user.email, recipient)
            assertNotNull(harness.repository.findVerificationToken(token))
        }
    }

    @Test
    fun `registering with a weak password is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.createUser(password = "weak", email = "user@example.com", name = "User")

            assertError(RegisterError.WEAK_PASSWORD, result)
        }
    }

    @Test
    fun `registering with an invalid email is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.createUser(password = "Str0ngPass", email = "not-an-email", name = "User")

            assertError(RegisterError.INVALID_EMAIL, result)
        }
    }

    @Test
    fun `registering the same email twice is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(password = "Str0ngPass", email = "user@example.com", name = "User")

            val result = harness.service.createUser(password = "Str0ngPass", email = "user@example.com", name = "User")

            assertError(RegisterError.ALREADY_EXISTS, result)
        }
    }

    @Test
    fun `registering with a different case and surrounding whitespace still collides on email`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(password = "Str0ngPass", email = "user@example.com", name = "User")

            val result =
                harness.service.createUser(password = "Str0ngPass", email = "  User@Example.COM  ", name = "User Two")

            assertError(RegisterError.ALREADY_EXISTS, result)
        }
    }

    @Test
    fun `cancellation while sending the verification email propagates instead of being swallowed`() {
        runBlocking {
            val harness = newHarness()
            harness.emailService.failureToThrow = CancellationException("client disconnected")

            assertFailsWith<CancellationException> {
                harness.service.createUser(password = "Str0ngPass", email = "user@example.com", name = "User")
            }
        }
    }
}
