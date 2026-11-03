/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleAssignment.routing.dto.response.ActiveVehicleAssignmentResponseDto
import com.mozgobolt.feature.vehicleAssignment.routing.dto.response.CurrentVendorAssignmentResponseDto
import com.mozgobolt.feature.vehicleAssignment.routing.dto.response.VehicleAssignmentResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleAssignmentRoutesTest {
    private class FakeVehicleAssignmentService : VehicleAssignmentService {
        var linkResult: AppResult<VehicleAssignment, VehicleAssignmentError> =
            AppResult.Success(
                VehicleAssignment(id = 1, vehicleId = 10, vendorUserId = 42, startedAt = Instant.EPOCH),
            )
        var unlinkResult: AppResult<Unit, VehicleAssignmentError> = AppResult.Success(Unit)
        var endActiveAssignmentResult: AppResult<Unit, VehicleAssignmentError> = AppResult.Success(Unit)
        val linkCalls = mutableListOf<Pair<Int, Int>>()
        val locationSharingChoices = mutableListOf<Boolean>()
        val unlinkCalls = mutableListOf<Pair<Int, Int>>()
        val endActiveAssignmentCalls = mutableListOf<Pair<Int, Int>>()
        var activeAssignment: ActiveVehicleAssignment? = null
        val activeAssignmentLookups = mutableListOf<Int>()

        override suspend fun link(
            vendorUserId: Int,
            vehicleId: Int,
        ): AppResult<VehicleAssignment, VehicleAssignmentError> {
            linkCalls += vendorUserId to vehicleId
            return linkResult
        }

        override suspend fun link(
            vendorUserId: Int,
            vehicleId: Int,
            shareLocationWithCustomers: Boolean,
        ): AppResult<VehicleAssignment, VehicleAssignmentError> {
            locationSharingChoices += shareLocationWithCustomers
            linkCalls += vendorUserId to vehicleId
            return when (val result = linkResult) {
                is AppResult.Success -> {
                    AppResult.Success(
                        result.data.copy(
                            locationSharingConsentedAt = Instant.EPOCH.takeIf { shareLocationWithCustomers },
                        ),
                    )
                }

                is AppResult.Error -> {
                    result
                }
            }
        }

        override suspend fun unlink(
            vendorUserId: Int,
            vehicleId: Int,
        ): AppResult<Unit, VehicleAssignmentError> {
            unlinkCalls += vendorUserId to vehicleId
            return unlinkResult
        }

        override suspend fun endActiveAssignment(
            adminUserId: Int,
            vehicleId: Int,
        ): AppResult<Unit, VehicleAssignmentError> {
            endActiveAssignmentCalls += adminUserId to vehicleId
            return endActiveAssignmentResult
        }

        override suspend fun endActiveAssignmentForVendorInCompany(
            vendorUserId: Int,
            companyId: Int,
        ) = error("not exercised by this test")

        override suspend fun endAllActiveAssignmentsForCompany(companyId: Int) = error("not exercised by this test")

        override suspend fun findAllForCompany(companyId: Int) = error("not exercised by this test")

        override fun notifyEndedAssignments(assignments: List<VehicleAssignment>) = Unit

        override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
            error("not exercised by this test")

        override suspend fun findActiveDetailsForVendor(vendorUserId: Int): ActiveVehicleAssignment? {
            activeAssignmentLookups += vendorUserId
            return activeAssignment
        }

        override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
            error("not exercised by this test")
    }

    @Test
    fun `current vendor assignment includes vehicle and company details`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleAssignmentService()
            service.activeAssignment =
                ActiveVehicleAssignment(
                    id = 12,
                    vehicleId = 34,
                    companyId = 56,
                    vehicleLabel = "Bakery Van",
                    licensePlate = "ABC-123",
                )
            application {
                installTestModules()
                routing { apiV1 { vehicleAssignmentRoutes(service) } }
            }

            val response =
                client.get("/api/v1/vehicles/assignments/me") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                CurrentVendorAssignmentResponseDto(
                    activeAssignment =
                        ActiveVehicleAssignmentResponseDto(
                            id = 12,
                            vehicleId = 34,
                            companyId = 56,
                            vehicleLabel = "Bakery Van",
                            licensePlate = "ABC-123",
                        ),
                ),
                Json.decodeFromString<CurrentVendorAssignmentResponseDto>(response.bodyAsText()),
            )
            assertEquals(listOf(42), service.activeAssignmentLookups)
        }

    @Test
    fun `current vendor assignment returns a null state when not linked`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleAssignmentService()
            application {
                installTestModules()
                routing { apiV1 { vehicleAssignmentRoutes(service) } }
            }

            val response =
                client.get("/api/v1/vehicles/assignments/me") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                CurrentVendorAssignmentResponseDto(activeAssignment = null),
                Json.decodeFromString<CurrentVendorAssignmentResponseDto>(response.bodyAsText()),
            )
        }

    @Test
    fun `current assignment is vendor-only`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleAssignmentService()
            application {
                installTestModules()
                routing { apiV1 { vehicleAssignmentRoutes(service) } }
            }

            val response =
                client.get("/api/v1/vehicles/assignments/me") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "BUYER"))
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(emptyList(), service.activeAssignmentLookups)
        }

    @Test
    fun `link unlink and end-active routes forward vendor id and vehicle id`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleAssignmentService()
            application {
                installTestModules()
                routing { apiV1 { vehicleAssignmentRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "VENDOR")

            val linked =
                client.post("/api/v1/vehicles/10/link") {
                    bearerAuth(token)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"shareLocationWithCustomers":true}""")
                }
            val unlinked =
                client.post("/api/v1/vehicles/11/unlink") {
                    bearerAuth(token)
                }
            val ended =
                client.post("/api/v1/vehicles/12/assignments/end-active") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, linked.status)
            assertEquals(
                VehicleAssignmentResponseDto(
                    id = 1,
                    vehicleId = 10,
                    vendorUserId = 42,
                    sharesLocationWithCustomers = true,
                ),
                Json.decodeFromString(linked.bodyAsText()),
            )
            assertEquals(HttpStatusCode.OK, unlinked.status)
            assertEquals(HttpStatusCode.OK, ended.status)
            assertEquals(listOf(42 to 10), service.linkCalls)
            assertEquals(listOf(true), service.locationSharingChoices)
            assertEquals(listOf(42 to 11), service.unlinkCalls)
            assertEquals(listOf(42 to 12), service.endActiveAssignmentCalls)
        }

    @Test
    fun `link route maps every assignment domain error to its HTTP status`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleAssignmentService()
            application {
                installTestModules()
                routing { apiV1 { vehicleAssignmentRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "VENDOR")
            val mappings =
                listOf(
                    VehicleAssignmentError.VEHICLE_NOT_FOUND to HttpStatusCode.NotFound,
                    VehicleAssignmentError.FORBIDDEN to HttpStatusCode.Forbidden,
                    VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED to HttpStatusCode.Conflict,
                    VehicleAssignmentError.VEHICLE_ARCHIVED to HttpStatusCode.Conflict,
                    VehicleAssignmentError.NOT_LINKED to HttpStatusCode.Conflict,
                    VehicleAssignmentError.NOT_ADMIN to HttpStatusCode.Forbidden,
                    VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT to HttpStatusCode.Conflict,
                )

            mappings.forEach { (error, expectedStatus) ->
                service.linkResult = AppResult.Error(error)

                val response =
                    client.post("/api/v1/vehicles/10/link") {
                        bearerAuth(token)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"shareLocationWithCustomers":false}""")
                    }

                assertEquals(expectedStatus, response.status, "wrong status for $error")
            }
            assertEquals(mappings.size, service.linkCalls.size)
        }

    @Test
    fun `malformed vehicle ids are rejected on all assignment mutation routes`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleAssignmentService()
            application {
                installTestModules()
                routing { apiV1 { vehicleAssignmentRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "VENDOR")

            val responses =
                listOf(
                    client.post("/api/v1/vehicles/not-a-number/link") {
                        bearerAuth(token)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"shareLocationWithCustomers":false}""")
                    },
                    client.post("/api/v1/vehicles/not-a-number/unlink") { bearerAuth(token) },
                    client.post("/api/v1/vehicles/not-a-number/assignments/end-active") { bearerAuth(token) },
                )

            responses.forEach { assertEquals(HttpStatusCode.BadRequest, it.status) }
            assertEquals(emptyList(), service.linkCalls)
            assertEquals(emptyList(), service.unlinkCalls)
            assertEquals(emptyList(), service.endActiveAssignmentCalls)
        }

    @Test
    fun `assignment mutation routes reject buyers before calling the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleAssignmentService()
            application {
                installTestModules()
                routing { apiV1 { vehicleAssignmentRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "BUYER")

            val responses =
                listOf(
                    client.post("/api/v1/vehicles/10/link") {
                        bearerAuth(token)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"shareLocationWithCustomers":false}""")
                    },
                    client.post("/api/v1/vehicles/10/unlink") { bearerAuth(token) },
                    client.post("/api/v1/vehicles/10/assignments/end-active") { bearerAuth(token) },
                )

            responses.forEach { assertEquals(HttpStatusCode.Forbidden, it.status) }
            assertEquals(emptyList(), service.linkCalls)
            assertEquals(emptyList(), service.unlinkCalls)
            assertEquals(emptyList(), service.endActiveAssignmentCalls)
        }
}
