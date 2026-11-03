/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordBreachCheckError
import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UserServiceCreateUserTest {
    @Test
    fun `registering a new user sends a verification email`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "New.User@Example.com",
                    name = "New User",
                    role = UserRole.BUYER,
                )

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val user = assertNotNull(harness.repository.findUser("new.user@example.com"))
            val queued = harness.emailOutbox.verificationEmails.single()
            assertEquals(user.id, queued.userId)
            assertEquals(queued.tokenHash, harness.tokenManager.hashTokenForStorage(queued.rawToken))
            assertNotNull(harness.repository.findVerificationToken(queued.tokenHash))
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
    fun `registration rejects a breached password before creating an account`() {
        runBlocking {
            val harness = newHarness()
            val password = "Str0ngPassphrase1"
            harness.passwordValidator.breachCheckResult = AppResult.Success(true)

            val result =
                harness.service.createUser(
                    password = password,
                    email = "breached@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )

            assertError(RegisterError.WEAK_PASSWORD, result)
            assertNull(harness.repository.findUser("breached@example.com"))
            assertEquals(listOf(password), harness.passwordValidator.checkedPasswords)
        }
    }

    @Test
    fun `registration fails closed when the breached password service is unavailable`() {
        runBlocking {
            val harness = newHarness()
            harness.passwordValidator.breachCheckResult =
                AppResult.Error(PasswordBreachCheckError.UNAVAILABLE)

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "check-unavailable@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )

            assertError(RegisterError.PASSWORD_CHECK_UNAVAILABLE, result)
            assertNull(harness.repository.findUser("check-unavailable@example.com"))
            assertTrue(harness.emailOutbox.verificationEmails.isEmpty())
        }
    }

    @Test
    fun `registering with an invalid email is rejected`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "not-an-email",
                    name = "User",
                    role = UserRole.BUYER,
                )

            assertError(RegisterError.INVALID_EMAIL, result)
        }
    }

    @Test
    fun `registering with an invalid display name is rejected by the use case`() {
        runBlocking {
            val invalidNames = listOf("", "a".repeat(101), " leading", "name<bad")

            invalidNames.forEachIndexed { index, name ->
                val harness = newHarness()
                val result =
                    harness.service.createUser(
                        password = "Str0ngPassphrase1",
                        email = "name-$index@example.com",
                        name = name,
                        role = UserRole.BUYER,
                    )

                assertError(RegisterError.INVALID_NAME, result)
                assertNull(harness.repository.findUser("name-$index@example.com"))
            }
        }
    }

    @Test
    fun `registering the same email twice is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createUser(
                password = "Str0ngPassphrase1",
                email = "user@example.com",
                name = "User",
                role = UserRole.BUYER,
            )

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
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
                password = "Str0ngPassphrase1",
                email = "user@example.com",
                name = "User",
                role = UserRole.BUYER,
            )

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
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
                    password = "Str0ngPassphrase1",
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
                    password = "Str0ngPassphrase1",
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
    fun `invalid WhatsApp and Viber numbers are rejected before user creation`() {
        runBlocking {
            val whatsappHarness = newHarness()
            val whatsappResult =
                whatsappHarness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "whatsapp@example.com",
                    name = "User",
                    role = UserRole.VENDOR,
                    whatsappNumber = "not-a-phone-number",
                )

            assertError(RegisterError.INVALID_PHONE_NUMBER, whatsappResult)
            assertNull(whatsappHarness.repository.findUser("whatsapp@example.com"))

            val viberHarness = newHarness()
            val viberResult =
                viberHarness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "viber@example.com",
                    name = "User",
                    role = UserRole.VENDOR,
                    viberNumber = "not-a-phone-number",
                )

            assertError(RegisterError.INVALID_PHONE_NUMBER, viberResult)
            assertNull(viberHarness.repository.findUser("viber@example.com"))
        }
    }

    @Test
    fun `an invalid Messenger username is rejected before user creation`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "messenger@example.com",
                    name = "User",
                    role = UserRole.VENDOR,
                    messengerUsername = "driver name!",
                )

            assertError(RegisterError.INVALID_MESSENGER_USERNAME, result)
            assertNull(harness.repository.findUser("messenger@example.com"))
        }
    }

    @Test
    fun `omitting the phone number leaves it null`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
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
    fun `cancellation while enqueuing the verification email propagates`() {
        runBlocking {
            val harness = newHarness()
            harness.emailOutbox.failureToThrow = CancellationException("client disconnected")

            assertFailsWith<CancellationException> {
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "user@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )
            }
        }
    }

    @Test
    fun `registration queues verification delivery without storing a raw token`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "email-failure@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                )

            result.fold(onSuccess = {}, onError = { fail("registration should remain successful: $it") })
            val user = assertNotNull(harness.repository.findUser("email-failure@example.com"))
            assertEquals(true, harness.repository.hasVerificationToken(user.id))
            val queued = harness.emailOutbox.verificationEmails.single()
            assertEquals(user.id, queued.userId)
            assertEquals(queued.tokenHash, harness.tokenManager.hashTokenForStorage(queued.rawToken))

            assertError(
                RegisterError.ALREADY_EXISTS,
                harness.service.createUser(
                    password = "Str0ngPassphrase1",
                    email = "email-failure@example.com",
                    name = "User",
                    role = UserRole.BUYER,
                ),
            )
        }
    }
}
