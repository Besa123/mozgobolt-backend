package com.mozgobolt.feature.user.service

import com.mozgobolt.feature.user.domain.model.CurrentUserProfile
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserServiceCurrentUserTest {
    @Test
    fun `find current user returns only current account fields including verification state`() =
        runBlocking {
            val harness = newHarness()
            val user =
                harness.repository.seedVerifiedUser(
                    email = "vendor@example.com",
                    password = "unused",
                    verified = false,
                    role = UserRole.VENDOR,
                )
            val contactInfo =
                UserContactInfo(
                    phoneNumber = "+36301234567",
                    phoneNumberVisible = false,
                    whatsappNumber = "+491701234567",
                    whatsappVisible = true,
                    viberNumber = null,
                    viberVisible = false,
                    messengerUsername = "vendor.driver",
                    messengerVisible = true,
                )
            harness.repository.updateContactInfo(user.id, contactInfo)

            assertEquals(
                CurrentUserProfile(
                    id = user.id,
                    email = user.email,
                    name = user.name,
                    role = UserRole.VENDOR,
                    emailVerified = false,
                    contactInfo = contactInfo,
                ),
                harness.service.findCurrentUser(user.id),
            )
        }

    @Test
    fun `find current user returns null for an unknown id`() =
        runBlocking {
            val harness = newHarness()

            assertNull(harness.service.findCurrentUser(999))
        }
}
