/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.feature.user.domain.model.ContactInfoError
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private val ALL_VISIBLE =
    UserContactInfo(
        phoneNumber = "+36301234567",
        phoneNumberVisible = true,
        whatsappNumber = "+491701234567",
        whatsappVisible = true,
        viberNumber = "+491701234567",
        viberVisible = true,
        messengerUsername = "driver.mozgobolt",
        messengerVisible = true,
    )

class UserServiceContactInfoTest {
    @Test
    fun `updating contact info for a missing user returns not found`() =
        runBlocking {
            val harness = newHarness()

            val result = harness.service.updateContactInfo(userId = 999, contactInfo = ALL_VISIBLE)

            result.fold(
                onSuccess = { fail("expected USER_NOT_FOUND but got success") },
                onError = { error -> assertEquals(ContactInfoError.USER_NOT_FOUND, error) },
            )
        }

    @Test
    fun `update persists a normalized value and it shows up for a buyer`() =
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser("driver@example.com", "pw", role = UserRole.VENDOR)

            harness.service.updateContactInfo(user.id, ALL_VISIBLE).fold(
                onSuccess = {},
                onError = { fail("expected success but got $it") },
            )

            val contactInfo = harness.service.findContactInfo(user.id)
            assertEquals("+36301234567", contactInfo?.phoneNumber)
            assertEquals("+491701234567", contactInfo?.whatsappNumber)
            assertEquals("driver.mozgobolt", contactInfo?.messengerUsername)
        }

    @Test
    fun `setting a value to null removes it`() =
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser("driver@example.com", "pw", role = UserRole.VENDOR)
            harness.service.updateContactInfo(user.id, ALL_VISIBLE)

            harness.service.updateContactInfo(user.id, ALL_VISIBLE.copy(phoneNumber = null))

            assertNull(harness.service.findContactInfo(user.id)?.phoneNumber)
        }

    @Test
    fun `hiding a value keeps it stored but excludes it from what a buyer sees, and it round-trips back`() =
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser("driver@example.com", "pw", role = UserRole.VENDOR)
            harness.service.updateContactInfo(user.id, ALL_VISIBLE)

            harness.service.updateContactInfo(user.id, ALL_VISIBLE.copy(whatsappVisible = false))
            assertNull(harness.service.findContactInfo(user.id)?.whatsappNumber)

            harness.service.updateContactInfo(user.id, ALL_VISIBLE.copy(whatsappVisible = true))
            assertEquals("+491701234567", harness.service.findContactInfo(user.id)?.whatsappNumber)
        }

    @Test
    fun `a null value is never shown regardless of its visibility flag`() =
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser("driver@example.com", "pw", role = UserRole.VENDOR)

            harness.service.updateContactInfo(user.id, ALL_VISIBLE.copy(viberNumber = null, viberVisible = true))

            assertNull(harness.service.findContactInfo(user.id)?.viberNumber)
        }

    @Test
    fun `an invalid phone number is rejected and nothing is persisted`() =
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser("driver@example.com", "pw", role = UserRole.VENDOR)

            val result = harness.service.updateContactInfo(user.id, ALL_VISIBLE.copy(phoneNumber = "not-a-number"))

            result.fold(
                onSuccess = { fail("expected an error but got success") },
                onError = { error -> assertEquals(ContactInfoError.INVALID_PHONE_NUMBER, error) },
            )
            assertNull(harness.service.findContactInfo(user.id)?.phoneNumber)
        }

    @Test
    fun `invalid WhatsApp Viber and Messenger values are rejected without partial updates`() =
        runBlocking {
            val invalidInputs =
                listOf(
                    ALL_VISIBLE.copy(whatsappNumber = "not-a-phone-number") to ContactInfoError.INVALID_WHATSAPP_NUMBER,
                    ALL_VISIBLE.copy(viberNumber = "not-a-phone-number") to ContactInfoError.INVALID_VIBER_NUMBER,
                    ALL_VISIBLE.copy(messengerUsername = "driver name!") to ContactInfoError.INVALID_MESSENGER_USERNAME,
                )

            invalidInputs.forEachIndexed { index, (contactInfo, expectedError) ->
                val harness = newHarness()
                val user =
                    harness.repository.seedVerifiedUser(
                        email = "invalid-contact-$index@example.com",
                        password = "pw",
                        role = UserRole.VENDOR,
                    )

                val result = harness.service.updateContactInfo(user.id, contactInfo)

                result.fold(
                    onSuccess = { fail("expected $expectedError but got success") },
                    onError = { actual -> assertEquals(expectedError, actual) },
                )
                assertNull(harness.service.findContactInfo(user.id)?.phoneNumber)
                assertNull(harness.service.findContactInfo(user.id)?.whatsappNumber)
                assertNull(harness.service.findContactInfo(user.id)?.viberNumber)
                assertNull(harness.service.findContactInfo(user.id)?.messengerUsername)
            }
        }
}
