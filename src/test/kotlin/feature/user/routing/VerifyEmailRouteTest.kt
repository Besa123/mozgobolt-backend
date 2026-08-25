package com.besa.shelflife.feature.user.routing

import com.besa.shelflife.core.configureTestEnvironment
import com.besa.shelflife.core.domain.AppResult
import com.besa.shelflife.feature.user.domain.model.VerifyEmailError
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifyEmailRouteTest {
    @Test
    fun `a valid token returns 200`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = client.get("${AuthPaths.VERIFY_EMAIL}?token=some-token")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("some-token", userService.lastVerifyEmailToken)
        }

    @Test
    fun `a missing token query parameter returns 400 without calling the service`() =
        testApplication {
            configureTestEnvironment()
            val userService = FakeUserService()
            application { installAuthRoutesTestApp(userService) }

            val response = client.get(AuthPaths.VERIFY_EMAIL)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("MISSING_TOKEN", response.errorBody().error)
            assertEquals(null, userService.lastVerifyEmailToken)
        }

    @Test
    fun `an invalid token returns 400`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    verifyEmailResult =
                        AppResult.Error(VerifyEmailError.INVALID_TOKEN)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = client.get("${AuthPaths.VERIFY_EMAIL}?token=bad-token")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_TOKEN", response.errorBody().error)
        }

    @Test
    fun `an expired token returns 410 Gone`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    verifyEmailResult =
                        AppResult.Error(VerifyEmailError.EXPIRED_TOKEN)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = client.get("${AuthPaths.VERIFY_EMAIL}?token=expired-token")

            assertEquals(HttpStatusCode.Gone, response.status)
            assertEquals("EXPIRED_TOKEN", response.errorBody().error)
        }

    @Test
    fun `an already-verified account returns 409`() =
        testApplication {
            configureTestEnvironment()
            val userService =
                FakeUserService().apply {
                    verifyEmailResult =
                        AppResult.Error(VerifyEmailError.ALREADY_VERIFIED)
                }
            application { installAuthRoutesTestApp(userService) }

            val response = client.get("${AuthPaths.VERIFY_EMAIL}?token=already-used-token")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("ALREADY_VERIFIED", response.errorBody().error)
        }
}
