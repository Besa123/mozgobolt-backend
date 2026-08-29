package com.shelflife.feature.user.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.user.domain.model.VerifyEmailError
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
    fun `each VerifyEmailError maps to its documented status code`() {
        val expectedStatusByError =
            mapOf(
                VerifyEmailError.INVALID_TOKEN to HttpStatusCode.BadRequest,
                VerifyEmailError.EXPIRED_TOKEN to HttpStatusCode.Gone,
                VerifyEmailError.ALREADY_VERIFIED to HttpStatusCode.Conflict,
            )
        // A `when` over VerifyEmailError in AuthService is exhaustive at compile time, but this
        // also guards against a future error variant being added there without a corresponding
        // case being added *here* too.
        assertEquals(VerifyEmailError.entries.toSet(), expectedStatusByError.keys)

        for ((error, expectedStatus) in expectedStatusByError) {
            testApplication {
                configureTestEnvironment()
                val userService = FakeUserService().apply { verifyEmailResult = AppResult.Error(error) }
                application { installAuthRoutesTestApp(userService) }

                val response = client.get("${AuthPaths.VERIFY_EMAIL}?token=some-token")

                assertEquals(expectedStatus, response.status, "expected $error to map to $expectedStatus")
                assertEquals(error.name, response.errorBody().error)
            }
        }
    }
}
