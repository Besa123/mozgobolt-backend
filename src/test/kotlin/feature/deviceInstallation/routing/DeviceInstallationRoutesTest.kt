package com.mozgobolt.feature.deviceInstallation.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationError
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import com.mozgobolt.feature.user.routing.postJson
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceInstallationRoutesTest {
    private class FakeDeviceInstallationService : DeviceInstallationService {
        var registerCallCount = 0
            private set
        var unregisterResult: AppResult<Unit, DeviceInstallationError> = AppResult.Success(Unit)

        override suspend fun registerInstallation(
            userId: Int,
            installationId: String,
            platform: DevicePlatform,
        ): DeviceInstallation {
            registerCallCount++
            return DeviceInstallation(1, userId, installationId, platform, Instant.now(), Instant.now())
        }

        override suspend fun unregisterInstallation(
            userId: Int,
            installationId: String,
        ): AppResult<Unit, DeviceInstallationError> = unregisterResult

        override suspend fun sendPush(
            userId: Int,
            data: Map<String, String>,
        ) = Unit
    }

    @Test
    fun `registering a device installation with a valid body succeeds`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeDeviceInstallationService()
            application {
                installTestModules()
                routing { apiV1 { deviceInstallationRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1)

            val response =
                postJson("/api/v1/device-installations", """{"installationId":"fcm-fid","platform":"ANDROID"}""") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, service.registerCallCount)
        }

    @Test
    fun `registering without authentication is rejected`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeDeviceInstallationService()
            application {
                installTestModules()
                routing { apiV1 { deviceInstallationRoutes(service) } }
            }

            val response =
                postJson("/api/v1/device-installations", """{"installationId":"fcm-fid","platform":"ANDROID"}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, service.registerCallCount)
        }

    @Test
    fun `registering a blank installation id is rejected before reaching the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeDeviceInstallationService()
            application {
                installTestModules()
                routing { apiV1 { deviceInstallationRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1)

            val response =
                postJson("/api/v1/device-installations", """{"installationId":"","platform":"ANDROID"}""") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.registerCallCount)
        }

    @Test
    fun `registering with an unknown platform value is rejected before reaching the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeDeviceInstallationService()
            application {
                installTestModules()
                routing { apiV1 { deviceInstallationRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1)

            val response =
                postJson(
                    "/api/v1/device-installations",
                    """{"installationId":"fcm-fid","platform":"WINDOWS_PHONE"}""",
                ) { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.registerCallCount)
        }

    @Test
    fun `unregistering an unknown installation id maps NOT_FOUND to a 404`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeDeviceInstallationService()
            service.unregisterResult = AppResult.Error(DeviceInstallationError.NOT_FOUND)
            application {
                installTestModules()
                routing { apiV1 { deviceInstallationRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1)

            val response = client.delete("/api/v1/device-installations/some-fid") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `unregistering without authentication is rejected`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeDeviceInstallationService()
            application {
                installTestModules()
                routing { apiV1 { deviceInstallationRoutes(service) } }
            }

            val response = client.delete("/api/v1/device-installations/some-fid")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
