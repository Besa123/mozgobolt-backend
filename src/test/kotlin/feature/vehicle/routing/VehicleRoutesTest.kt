package com.mozgobolt.feature.vehicle.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.user.routing.FakeUserService
import com.mozgobolt.feature.user.routing.postJson
import com.mozgobolt.feature.vehicle.domain.VehicleService
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleRoutesTest {
    private class FakeVehicleService : VehicleService {
        var createVehicleCallCount = 0
            private set
        var createVehicleResult: AppResult<Vehicle, VehicleError> =
            AppResult.Success(
                Vehicle(
                    id = 1,
                    companyId = 1,
                    label = "Truck 1",
                    licensePlate = "ABC-123",
                    pictureUrl = null,
                    createdAt = Instant.now(),
                ),
            )
        var findVehicleResult: AppResult<Vehicle, VehicleError> = AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
        var archiveVehicleCallCount = 0
            private set
        var archiveVehicleResult: AppResult<Unit, VehicleError> = AppResult.Success(Unit)
        var uploadVehiclePictureCallCount = 0
            private set
        var uploadVehiclePictureResult: AppResult<Vehicle, VehicleError> = createVehicleResult
        var getVehiclePictureResult: AppResult<ByteArray, VehicleError> =
            AppResult.Error(
                VehicleError.VEHICLE_NOT_FOUND,
            )

        override suspend fun createVehicle(
            adminUserId: Int,
            companyId: Int,
            label: String,
            licensePlate: String,
            pictureUrl: String?,
        ): AppResult<Vehicle, VehicleError> {
            createVehicleCallCount++
            return createVehicleResult
        }

        override suspend fun updateVehicle(
            adminUserId: Int,
            companyId: Int,
            vehicleId: Int,
            licensePlate: String,
            pictureUrl: String?,
        ): AppResult<Vehicle, VehicleError> = error("not exercised by this test")

        override suspend fun listCompanyVehicles(
            userId: Int,
            companyId: Int,
        ): AppResult<List<Vehicle>, VehicleError> = error("not exercised by this test")

        override suspend fun findVehicle(vehicleId: Int): AppResult<Vehicle, VehicleError> = findVehicleResult

        override suspend fun archiveVehicle(
            adminUserId: Int,
            companyId: Int,
            vehicleId: Int,
        ): AppResult<Unit, VehicleError> {
            archiveVehicleCallCount++
            return archiveVehicleResult
        }

        override suspend fun uploadVehiclePicture(
            adminUserId: Int,
            companyId: Int,
            vehicleId: Int,
            rawBytes: ByteArray,
        ): AppResult<Vehicle, VehicleError> {
            uploadVehiclePictureCallCount++
            return uploadVehiclePictureResult
        }

        override suspend fun getVehiclePicture(vehicleId: Int): AppResult<ByteArray, VehicleError> =
            getVehiclePictureResult
    }

    private class FakeVehicleAssignmentService : VehicleAssignmentService {
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

        override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? = null

        override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? = null
    }

    @Test
    fun `creating a vehicle twice with the same idempotency key does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")
            val body = """{"label":"Truck 1","licensePlate":"ABC-123"}"""

            val first =
                postJson("/api/v1/companies/1/vehicles", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "vehicle-key-1")
                }
            val second =
                postJson("/api/v1/companies/1/vehicles", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "vehicle-key-1")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals("true", second.headers["X-Idempotency-Replayed"])
            assertEquals(1, service.createVehicleCallCount, "the handler must not run a second time on replay")
        }

    @Test
    fun `creating a vehicle without an idempotency key calls the service every time, as normal`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")
            val body = """{"label":"Truck 1","licensePlate":"ABC-123"}"""

            postJson("/api/v1/companies/1/vehicles", body) { bearerAuth(token) }
            postJson("/api/v1/companies/1/vehicles", body) { bearerAuth(token) }

            assertEquals(2, service.createVehicleCallCount, "idempotency is opt-in via the header, never the default")
        }

    @Test
    fun `a buyer, not just a vendor, can fetch a single vehicle's details`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            service.findVehicleResult =
                AppResult.Success(
                    Vehicle(
                        id = 1,
                        companyId = 1,
                        label = "Truck 1",
                        licensePlate = "ABC-123",
                        pictureUrl = "https://example.com/truck.png",
                        createdAt = Instant.now(),
                    ),
                )
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.get("/api/v1/vehicles/1") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `fetching a single vehicle without authentication is rejected`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            FakeVehicleService(),
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }

            val response = client.get("/api/v1/vehicles/1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `fetching an unknown vehicle id returns not found`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            service.findVehicleResult = AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.get("/api/v1/vehicles/999") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `an admin can archive a vehicle`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response = client.post("/api/v1/companies/1/vehicles/1/archive") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(1, service.archiveVehicleCallCount)
        }

    @Test
    fun `archiving a vehicle as a non-admin is rejected`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            service.archiveVehicleResult = AppResult.Error(VehicleError.NOT_ADMIN)
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response = client.post("/api/v1/companies/1/vehicles/1/archive") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `archiving a vehicle without authentication is rejected`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            FakeVehicleService(),
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/companies/1/vehicles/1/archive")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `an admin can upload a vehicle picture`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response = client.uploadPicture(token, "/api/v1/companies/1/vehicles/1/picture")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, service.uploadVehiclePictureCallCount)
        }

    @Test
    fun `uploading a vehicle picture as a non-admin is rejected`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            service.uploadVehiclePictureResult = AppResult.Error(VehicleError.NOT_ADMIN)
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response = client.uploadPicture(token, "/api/v1/companies/1/vehicles/1/picture")

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `an invalid image is rejected`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            service.uploadVehiclePictureResult = AppResult.Error(VehicleError.INVALID_IMAGE)
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response = client.uploadPicture(token, "/api/v1/companies/1/vehicles/1/picture")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `fetching a vehicle's picture returns its bytes when one exists`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeVehicleService()
            service.getVehiclePictureResult = AppResult.Success(byteArrayOf(1, 2, 3))
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            service,
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.get("/api/v1/vehicles/1/picture") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `fetching a vehicle's picture when none exists returns not found`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing {
                    apiV1 {
                        vehicleRoutes(
                            FakeVehicleService(),
                            FakeVehicleAssignmentService(),
                            FakeUserService(),
                            FakeIdempotencyStore(),
                        )
                    }
                }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.get("/api/v1/vehicles/1/picture") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private suspend fun io.ktor.client.HttpClient.uploadPicture(
        token: String,
        path: String,
    ) = submitFormWithBinaryData(
        url = path,
        formData =
            formData {
                append(
                    "file",
                    byteArrayOf(1, 2, 3),
                    Headers.build { append(HttpHeaders.ContentDisposition, "filename=test.jpg") },
                )
            },
    ) { bearerAuth(token) }
}
