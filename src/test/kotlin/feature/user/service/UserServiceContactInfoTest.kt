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
}
