package com.mozgobolt.feature.user.service

import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

class UserServiceCreateUserTest {
    @Test
    fun `registering a new user sends a verification email`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "New.User@Example.com",
                    name = "New User",
                    role = UserRole.BUYER,
                )

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val user = assertNotNull(harness.repository.findUser("new.user@example.com"))
            val (recipient, token) = harness.emailService.sentTokens.single()
            assertEquals(user.email, recipient)
            assertNotNull(harness.repository.findVerificationToken(harness.tokenManager.hashTokenForStorage(token)))
        }
    }

    @Test
    fun `registering with a weak password is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "weak",
                    email = "user@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )

            assertError(RegisterError.WEAK_PASSWORD, result)
        }
    }

    @Test
    fun `registering with an invalid email is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "not-an-email",
                    name = "User",
                    role = UserRole.BUYER,
                )

            assertError(RegisterError.INVALID_EMAIL, result)
        }
    }

    @Test
    fun `registering the same email twice is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "User",
                role = UserRole.BUYER,
            )

            val result =
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "user@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )

            assertError(RegisterError.ALREADY_EXISTS, result)
        }
    }

    @Test
    fun `registering with a different case and surrounding whitespace still collides on email`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "User",
                role = UserRole.BUYER,
            )

            val result =
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "  User@Example.COM  ",
                    name = "User Two",
                    role = UserRole.BUYER,
                )

            assertError(RegisterError.ALREADY_EXISTS, result)
        }
    }

    @Test
    fun `a valid Hungarian phone number is normalized to E164 before being persisted`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "user@example.com",
                    name = "User",
                    role = UserRole.VENDOR,
                    phoneNumber = "06-20-123-4567",
                )

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val user = assertNotNull(harness.repository.findUser("user@example.com"))
            assertEquals("+36201234567", user.phoneNumber)
        }
    }

    @Test
    fun `a non-Hungarian phone number is rejected even though it's a structurally valid number`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "user@example.com",
                    name = "User",
                    role = UserRole.VENDOR,
                    // A real, validly-formatted US number — must be rejected for being the wrong
                    // country, exercising the same defense-in-depth check the request DTO
                    // already applies, in case createUser is ever called without DTO validation.
                    phoneNumber = "+14155552671",
                )

            assertError(RegisterError.INVALID_PHONE_NUMBER, result)
            assertNull(harness.repository.findUser("user@example.com"))
        }
    }

    @Test
    fun `omitting the phone number leaves it null`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "user@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val user = assertNotNull(harness.repository.findUser("user@example.com"))
            assertNull(user.phoneNumber)
        }
    }

    @Test
    fun `cancellation while sending the verification email propagates instead of being swallowed`() {
        runBlocking {
            val harness = newHarness()
            harness.emailService.failureToThrow = CancellationException("client disconnected")

            assertFailsWith<CancellationException> {
                harness.service.createUser(
                    password = "Str0ngPass",
                    email = "user@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )
            }
        }
    }
}
