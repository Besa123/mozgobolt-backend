package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.domain.model.CurrentUserProfile
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.routing.dto.response.ContactInfoResponseDto
import com.mozgobolt.feature.user.routing.dto.response.CurrentUserResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrentUserRouteTest {
    @Test
    fun `authenticated buyer and vendor can load their current account`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val buyer =
                CurrentUserProfile(
                    id = 7,
                    email = "buyer@example.com",
                    name = "Buyer",
                    role = UserRole.BUYER,
                    emailVerified = true,
                    contactInfo =
                        UserContactInfo(
                            phoneNumber = "+36301234567",
                            phoneNumberVisible = true,
                            whatsappNumber = null,
                            whatsappVisible = false,
                            viberNumber = null,
                            viberVisible = false,
                            messengerUsername = null,
                            messengerVisible = false,
                        ),
                )
            userService.findCurrentUserResult = buyer
            val buyerResponse =
                client.get(AuthPaths.CURRENT_USER) {
                    bearerAuth(testAccessTokenFor(userId = buyer.id, role = buyer.role.name))
                }

            assertEquals(HttpStatusCode.OK, buyerResponse.status)
            assertEquals(
                CurrentUserResponseDto(
                    id = "7",
                    email = buyer.email,
                    name = buyer.name,
                    role = "BUYER",
                    emailVerified = true,
                    contactInfo =
                        ContactInfoResponseDto(
                            phoneNumber = "+36301234567",
                            phoneNumberVisible = true,
                            whatsappNumber = null,
                            whatsappVisible = false,
                            viberNumber = null,
                            viberVisible = false,
                            messengerUsername = null,
                            messengerVisible = false,
                        ),
                ),
                Json.decodeFromString<CurrentUserResponseDto>(buyerResponse.bodyAsText()),
            )

            val vendor =
                CurrentUserProfile(
                    id = 8,
                    email = "vendor@example.com",
                    name = "Vendor",
                    role = UserRole.VENDOR,
                    emailVerified = false,
                    contactInfo =
                        UserContactInfo(
                            phoneNumber = null,
                            phoneNumberVisible = false,
                            whatsappNumber = "+491701234567",
                            whatsappVisible = true,
                            viberNumber = null,
                            viberVisible = false,
                            messengerUsername = "vendor.driver",
                            messengerVisible = true,
                        ),
                )
            userService.findCurrentUserResult = vendor
            val vendorResponse =
                client.get(AuthPaths.CURRENT_USER) {
                    bearerAuth(testAccessTokenFor(userId = vendor.id, role = vendor.role.name))
                }

            assertEquals(HttpStatusCode.OK, vendorResponse.status)
            assertEquals(
                CurrentUserResponseDto(
                    id = "8",
                    email = vendor.email,
                    name = vendor.name,
                    role = "VENDOR",
                    emailVerified = false,
                    contactInfo =
                        ContactInfoResponseDto(
                            phoneNumber = null,
                            phoneNumberVisible = false,
                            whatsappNumber = "+491701234567",
                            whatsappVisible = true,
                            viberNumber = null,
                            viberVisible = false,
                            messengerUsername = "vendor.driver",
                            messengerVisible = true,
                        ),
                ),
                Json.decodeFromString<CurrentUserResponseDto>(vendorResponse.bodyAsText()),
            )
            assertEquals(listOf(buyer.id, vendor.id), userService.findCurrentUserCalls)
        }

    @Test
    fun `unauthenticated request is rejected`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = client.get(AuthPaths.CURRENT_USER)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), userService.findCurrentUserCalls)
        }

    @Test
    fun `missing authenticated user returns not found`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response =
                client.get(AuthPaths.CURRENT_USER) {
                    bearerAuth(testAccessTokenFor(userId = 99))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("USER_NOT_FOUND", response.errorBody().error)
        }
}
