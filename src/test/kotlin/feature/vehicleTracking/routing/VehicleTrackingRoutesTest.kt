/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.routing

import com.mozgobolt.core.TEST_JWT_AUDIENCE
import com.mozgobolt.core.TEST_JWT_ISSUER
import com.mozgobolt.core.TEST_JWT_SECRET
import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.security.JwtTokenManager
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.core.testAccessTokenSessionValidator
import com.mozgobolt.feature.user.routing.postJson
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class VehicleTrackingRoutesTest {
    private class FakeVehicleTrackingService : VehicleTrackingService {
        var result: AppResult<Unit, TelemetryError> = AppResult.Success(Unit)
        var callCount = 0
            private set
        var lastCall: Pair<Int, List<TelemetryPoint>>? = null
            private set

        override suspend fun ingestTelemetry(
            vendorUserId: Int,
            points: List<TelemetryPoint>,
        ): AppResult<Unit, TelemetryError> {
            callCount++
            lastCall = vendorUserId to points
            return result
        }
    }

    @Test
    fun `telemetry route parses timestamps maps service outcomes and enforces vendor access`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleTrackingService()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleTrackingRoutes(
                            service,
                            NoOpVehicleLocationHub(),
                            testAccessTokenSessionValidator(),
                        )
                    }
                }
            }
            val recordedAt = Instant.now().minusSeconds(30)
            val body =
                """{"points":[{"latitude":47.4979,"longitude":19.0402,"recordedAt":"$recordedAt"}]}"""

            val accepted =
                postJson("/api/v1/vehicle-tracking/telemetry", body) {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertEquals(42 to listOf(TelemetryPoint(47.4979, 19.0402, recordedAt)), service.lastCall)

            service.result = AppResult.Error(TelemetryError.NOT_LINKED_TO_VEHICLE)
            val unlinked =
                postJson("/api/v1/vehicle-tracking/telemetry", body) {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }
            assertEquals(HttpStatusCode.Conflict, unlinked.status)

            val buyer =
                postJson("/api/v1/vehicle-tracking/telemetry", body) {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "BUYER"))
                }
            val unauthenticated = postJson("/api/v1/vehicle-tracking/telemetry", body)

            assertEquals(HttpStatusCode.Forbidden, buyer.status)
            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(2, service.callCount, "rejected requests must not reach the service")
        }

    @Test
    fun `live route accepts nearby and rejects the nationwide feed`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleTrackingRoutes(
                            FakeVehicleTrackingService(),
                            NoOpVehicleLocationHub(),
                            testAccessTokenSessionValidator(),
                        )
                    }
                }
            }
            val token =
                JwtTokenManager(
                    secret = TEST_JWT_SECRET,
                    audience = TEST_JWT_AUDIENCE,
                    issuer = TEST_JWT_ISSUER,
                    accessTokenExpiration = 30.seconds,
                ).generateAccessToken(userId = 42, role = "BUYER", familyId = "tracking-test-family")

            val nationwide =
                client.get("/api/v1/vehicle-tracking/live?scope=all") {
                    bearerAuth(token)
                }
            val nearby =
                client.get("/api/v1/vehicle-tracking/live?scope=nearby&lat=47.4979&lon=19.0402") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, nationwide.status)
            assertTrue(nationwide.bodyAsText().contains("INVALID_REQUEST"))
            assertEquals(HttpStatusCode.OK, nearby.status)
        }

    @Test
    fun `live route is buyer-only`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleTrackingRoutes(
                            FakeVehicleTrackingService(),
                            NoOpVehicleLocationHub(),
                            testAccessTokenSessionValidator(),
                        )
                    }
                }
            }

            val response =
                client.get("/api/v1/vehicle-tracking/live?scope=nearby&lat=47.4979&lon=19.0402") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `invalid live request is returned as an SSE error event`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleTrackingRoutes(
                            FakeVehicleTrackingService(),
                            NoOpVehicleLocationHub(),
                            testAccessTokenSessionValidator(),
                        )
                    }
                }
            }

            val response =
                client.get("/api/v1/vehicle-tracking/live?scope=all-the-things") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "BUYER"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("INVALID_REQUEST"))
        }

    @Test
    fun `live route emits snapshot positions and offline events`() =
        testApplication {
            configureTestEnvironment()
            val update =
                VehicleLocationUpdate(
                    vehicleId = 12,
                    vendorUserId = 42,
                    latitude = 47.5,
                    longitude = 19.0,
                    recordedAt = Instant.parse("2026-10-31T12:34:56Z"),
                    cellId = CellId("opaque-cell"),
                    assignmentId = 99,
                    sharesLocationWithCustomers = true,
                )
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleTrackingRoutes(
                            FakeVehicleTrackingService(),
                            LocationEventHub(update),
                            testAccessTokenSessionValidator(),
                        )
                    }
                }
            }
            val token =
                JwtTokenManager(
                    secret = TEST_JWT_SECRET,
                    audience = TEST_JWT_AUDIENCE,
                    issuer = TEST_JWT_ISSUER,
                    accessTokenExpiration = 10.seconds,
                ).generateAccessToken(userId = 42, role = "BUYER", familyId = "tracking-test-family")

            val response =
                client.get("/api/v1/vehicle-tracking/live?scope=nearby&lat=47.5&lon=19.0") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val events = response.bodyAsText()
            assertTrue(events.contains("location"))
            assertTrue(events.contains("offline"))
            assertTrue(events.contains("reconnect"))
            assertTrue(events.contains("\"vehicleId\":12"))
            assertFalse(events.contains("assignmentId"))
        }

    private class LocationEventHub(
        private val update: VehicleLocationUpdate,
    ) : VehicleLocationHub {
        override fun publish(update: VehicleLocationUpdate) = Unit

        override fun markOffline(vehicleId: Int) = Unit

        override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? =
            update.takeIf {
                it.vehicleId ==
                    vehicleId
            }

        override fun subscribeNear(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): Flow<VehicleLiveEvent> =
            flowOf(VehicleLiveEvent.Position(update), VehicleLiveEvent.Offline(update.vehicleId))

        override fun snapshotNear(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): List<VehicleLocationUpdate> = listOf(update)
    }
}
