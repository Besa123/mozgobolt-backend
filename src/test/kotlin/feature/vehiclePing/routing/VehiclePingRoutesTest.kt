package com.mozgobolt.feature.vehiclePing.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.routing.postJson
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import com.mozgobolt.feature.vehiclePing.service.InMemoryVehiclePingHub
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private const val VALID_PING_BODY = """{"latitude":47.4979,"longitude":19.0402}"""

class VehiclePingRoutesTest {
    private class FakeVehiclePingService : VehiclePingService {
        var pingCallCount = 0
            private set
        var pingResult: AppResult<VehiclePing, PingError> =
            AppResult.Success(VehiclePing(id = 1, vehicleId = 10, buyerUserId = 1, sentAt = Instant.now()))

        override suspend fun ping(
            buyerUserId: Int,
            vehicleId: Int,
            latitude: Double,
            longitude: Double,
        ): AppResult<VehiclePing, PingError> {
            pingCallCount++
            return pingResult
        }
    }

    private fun Application.installApp(
        service: VehiclePingService = FakeVehiclePingService(),
        hub: VehiclePingHub = InMemoryVehiclePingHub(),
    ) {
        installTestModules()
        routing { apiV1 { vehiclePingRoutes(service, hub) } }
    }

    @Test
    fun `a buyer pinging a vehicle gets accepted`() =
        testApplication {
            configureTestEnvironment()
            application { installApp() }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) { bearerAuth(token) }

            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `a vendor pinging a vehicle is forbidden — only buyers may ping`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response = postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) { bearerAuth(token) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(0, service.pingCallCount, "the service must never be reached for the wrong role")
        }

    @Test
    fun `pinging without a token is unauthorized`() =
        testApplication {
            configureTestEnvironment()
            application { installApp() }

            val response = postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) {}

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `an invalid vehicle id in the path is rejected before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = postJson("/api/v1/vehicles/not-a-number/ping", VALID_PING_BODY) { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.pingCallCount)
        }

    @Test
    fun `an out-of-range latitude is rejected before reaching the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")
            val body = """{"latitude":190.0,"longitude":19.0402}"""

            val response = postJson("/api/v1/vehicles/10/ping", body) { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.pingCallCount)
        }

    @Test
    fun `an out-of-range longitude is rejected before reaching the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")
            val body = """{"latitude":47.4979,"longitude":-190.0}"""

            val response = postJson("/api/v1/vehicles/10/ping", body) { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.pingCallCount)
        }

    @Test
    fun `a vehicle with no active assignment maps to conflict`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            service.pingResult = AppResult.Error(PingError.VEHICLE_NOT_ACTIVE)
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) { bearerAuth(token) }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `a cooldown-active rejection maps to too many requests`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            service.pingResult = AppResult.Error(PingError.COOLDOWN_ACTIVE)
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) { bearerAuth(token) }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }

    @Test
    fun `pinging an unknown vehicle maps to not found`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            service.pingResult = AppResult.Error(PingError.VEHICLE_NOT_FOUND)
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = postJson("/api/v1/vehicles/999/ping", VALID_PING_BODY) { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
