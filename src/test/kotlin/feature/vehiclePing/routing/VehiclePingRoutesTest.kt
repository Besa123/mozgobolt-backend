/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.routing

import com.mozgobolt.core.TEST_JWT_AUDIENCE
import com.mozgobolt.core.TEST_JWT_ISSUER
import com.mozgobolt.core.TEST_JWT_SECRET
import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.data.idempotency.IDEMPOTENCY_KEY_HEADER
import com.mozgobolt.core.data.security.JwtTokenManager
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.routing.postJson
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingOutcome
import com.mozgobolt.feature.vehiclePing.service.InMemoryVehiclePingHub
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val VALID_PING_BODY = """{"latitude":47.4979,"longitude":19.0402}"""

class VehiclePingRoutesTest {
    private class FakeVehiclePingService : VehiclePingService {
        var pingCallCount = 0
            private set
        var dispatchPingCallCount = 0
            private set
        var pingResult: AppResult<VehiclePingOutcome, PingError> =
            AppResult.Success(
                VehiclePingOutcome(
                    ping = VehiclePing(vehicleId = 10, buyerUserId = 1, sentAt = Instant.now()),
                    vendorUserId = 500,
                    notification =
                        VehiclePingNotification(
                            vehicleId = 10,
                            sentAt = Instant.now(),
                            latitude = 47.5,
                            longitude = 19.0,
                        ),
                ),
            )

        override suspend fun ping(
            buyerUserId: Int,
            vehicleId: Int,
            latitude: Double,
            longitude: Double,
        ): AppResult<VehiclePingOutcome, PingError> {
            pingCallCount++
            return pingResult
        }

        override suspend fun dispatchPing(outcome: VehiclePingOutcome) {
            dispatchPingCallCount++
        }
    }

    private fun Application.installApp(
        service: VehiclePingService = FakeVehiclePingService(),
        hub: VehiclePingHub = InMemoryVehiclePingHub(),
        idempotencyStore: FakeIdempotencyStore = FakeIdempotencyStore(),
    ) {
        installTestModules()
        routing {
            apiV1 {
                vehiclePingRoutes(
                    service,
                    hub,
                    idempotencyStore,
                    com.mozgobolt.core.testAccessTokenSessionValidator(),
                )
            }
        }
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
    fun `retrying a ping with the same idempotency key replays the accepted response`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installApp(service, idempotencyStore = idempotencyStore) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val first =
                postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) {
                    bearerAuth(token)
                    header(IDEMPOTENCY_KEY_HEADER, "ping-retry-key")
                }
            val replay =
                postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) {
                    bearerAuth(token)
                    header(IDEMPOTENCY_KEY_HEADER, "ping-retry-key")
                }

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Accepted, replay.status)
            assertEquals("true", replay.headers["X-Idempotency-Replayed"])
            assertEquals(1, service.pingCallCount)
            assertEquals(1, service.dispatchPingCallCount, "a replay must not dispatch the ping twice")
        }

    @Test
    fun `an idempotency key cannot be reused for a different vehicle`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installApp(service, idempotencyStore = idempotencyStore) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val first =
                postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) {
                    bearerAuth(token)
                    header(IDEMPOTENCY_KEY_HEADER, "ping-conflict-key")
                }
            val conflicting =
                postJson("/api/v1/vehicles/11/ping", VALID_PING_BODY) {
                    bearerAuth(token)
                    header(IDEMPOTENCY_KEY_HEADER, "ping-conflict-key")
                }

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, conflicting.status)
            assertEquals(1, service.pingCallCount)
        }

    @Test
    fun `an idempotency key cannot replay another buyer's ping`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            val idempotencyStore = FakeIdempotencyStore()
            application { installApp(service, idempotencyStore = idempotencyStore) }

            val first =
                postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) {
                    bearerAuth(testAccessTokenFor(userId = 1, role = "BUYER"))
                    header(IDEMPOTENCY_KEY_HEADER, "ping-user-scope-key")
                }
            val conflicting =
                postJson("/api/v1/vehicles/10/ping", VALID_PING_BODY) {
                    bearerAuth(testAccessTokenFor(userId = 2, role = "BUYER"))
                    header(IDEMPOTENCY_KEY_HEADER, "ping-user-scope-key")
                }

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, conflicting.status)
            assertEquals(1, service.pingCallCount)
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
    fun `an overflowing vehicle id in the path is rejected before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehiclePingService()
            application { installApp(service) }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response =
                postJson("/api/v1/vehicles/999999999999999999999/ping", VALID_PING_BODY) { bearerAuth(token) }

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

    @Test
    fun `vendor live route streams ping notifications while buyers do not subscribe`() =
        testApplication {
            configureTestEnvironment()
            val subscribedUsers = mutableListOf<Int>()
            val hub =
                object : VehiclePingHub {
                    override fun publish(
                        vendorUserId: Int,
                        notification: VehiclePingNotification,
                    ) = Unit

                    override fun subscribe(vendorUserId: Int): Flow<VehiclePingNotification> {
                        subscribedUsers += vendorUserId
                        return flowOf(
                            VehiclePingNotification(
                                vehicleId = 12,
                                sentAt = Instant.parse("2026-01-02T03:04:05Z"),
                                latitude = 47.5,
                                longitude = 19.0,
                            ),
                        )
                    }
                }
            application { installApp(hub = hub) }
            val tokenManager =
                JwtTokenManager(
                    secret = TEST_JWT_SECRET,
                    audience = TEST_JWT_AUDIENCE,
                    issuer = TEST_JWT_ISSUER,
                    accessTokenExpiration = 10.seconds,
                )

            val vendorResponse =
                client.get("/api/v1$VEHICLE_PING_LIVE_PATH") {
                    bearerAuth(
                        tokenManager.generateAccessToken(
                            userId = 42,
                            role = "VENDOR",
                            familyId = "vendor-test-family",
                        ),
                    )
                }
            val buyerResponse =
                client.get("/api/v1$VEHICLE_PING_LIVE_PATH") {
                    bearerAuth(
                        tokenManager.generateAccessToken(
                            userId = 43,
                            role = "BUYER",
                            familyId = "buyer-test-family",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, vendorResponse.status)
            val vendorEvents = vendorResponse.bodyAsText()
            assertTrue(vendorEvents.contains("ping"))
            assertTrue(vendorEvents.contains("\"vehicleId\":12"))
            assertTrue(vendorEvents.contains("reconnect"))
            assertEquals(HttpStatusCode.Forbidden, buyerResponse.status)
            assertEquals(listOf(42), subscribedUsers)
        }
}
