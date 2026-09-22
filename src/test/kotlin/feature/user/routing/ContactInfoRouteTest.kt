package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.domain.model.ContactInfoError
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

private const val REQUEST_BODY = """
    {
        "phoneNumber": "+36301234567",
        "phoneNumberVisible": true,
        "whatsappNumber": null,
        "whatsappVisible": true,
        "viberNumber": null,
        "viberVisible": true,
        "messengerUsername": null,
        "messengerVisible": true
    }
"""

class ContactInfoRouteTest {
    @Test
    fun `an authenticated user can update their contact info`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            userService.updateContactInfoResult =
                AppResult.Success(
                    UserContactInfo(
                        phoneNumber = "+36301234567",
                        phoneNumberVisible = true,
                        whatsappNumber = null,
                        whatsappVisible = true,
                        viberNumber = null,
                        viberVisible = true,
                        messengerUsername = null,
                        messengerVisible = true,
                    ),
                )
            application { installAuthRoutesTestApp(userService) }

            val response =
                patchJson(AuthPaths.CONTACT_INFO, REQUEST_BODY) { bearerAuth(testAccessTokenFor(userId = 7)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(7, userService.lastUpdateContactInfoCall?.userId)
            assertEquals("+36301234567", userService.lastUpdateContactInfoCall?.contactInfo?.phoneNumber)
        }

    @Test
    fun `an unauthenticated request is rejected`() =
        testApplication {
            configureTestEnvironment()
            application { installAuthRoutesTestApp(FakeUserService()) }

            val response = patchJson(AuthPaths.CONTACT_INFO, REQUEST_BODY)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `an invalid contact value is rejected with a bad request`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            userService.updateContactInfoResult = AppResult.Error(ContactInfoError.INVALID_PHONE_NUMBER)
            application { installAuthRoutesTestApp(userService) }

            val response =
                patchJson(AuthPaths.CONTACT_INFO, REQUEST_BODY) { bearerAuth(testAccessTokenFor(userId = 7)) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_PHONE_NUMBER", response.errorBody().error)
        }
}
