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
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleAssignmentRoutesTest {
    private class FakeVehicleAssignmentService : VehicleAssignmentService {
        var activeAssignment: ActiveVehicleAssignment? = null
        val activeAssignmentLookups = mutableListOf<Int>()

        override suspend fun link(
            vendorUserId: Int,
            vehicleId: Int,
        ): AppResult<VehicleAssignment, VehicleAssignmentError> = error("not exercised by this test")

        override suspend fun unlink(
            vendorUserId: Int,
            vehicleId: Int,
        ): AppResult<Unit, VehicleAssignmentError> = error("not exercised by this test")

        override suspend fun endActiveAssignment(
            adminUserId: Int,
            vehicleId: Int,
        ): AppResult<Unit, VehicleAssignmentError> = error("not exercised by this test")

        override suspend fun endActiveAssignmentForVendorInCompany(
            vendorUserId: Int,
            companyId: Int,
        ) = error("not exercised by this test")

        override suspend fun endAllActiveAssignmentsForCompany(companyId: Int) = error("not exercised by this test")

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
}
