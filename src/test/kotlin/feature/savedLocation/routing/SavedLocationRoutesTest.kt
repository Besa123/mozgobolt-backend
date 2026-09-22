package com.mozgobolt.feature.savedLocation.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.savedLocation.domain.SavedLocationService
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.user.routing.postJson
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedLocationRoutesTest {
    private class FakeSavedLocationService : SavedLocationService {
        var createSavedLocationCallCount = 0
            private set
        var createSavedLocationResult: UserSavedLocation =
            UserSavedLocation(
                id = 1,
                userId = 1,
                label = "Home",
                latitude = 47.4979,
                longitude = 19.0402,
                radiusKm = 2.5,
                createdAt = Instant.now(),
            )
        var listSavedLocationsResult: List<UserSavedLocation> = emptyList()
        var deleteSavedLocationResult: AppResult<Unit, SavedLocationError> = AppResult.Success(Unit)

        override suspend fun createSavedLocation(
            userId: Int,
            label: String,
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): UserSavedLocation {
            createSavedLocationCallCount++
            return createSavedLocationResult
        }

        override suspend fun listSavedLocations(userId: Int): List<UserSavedLocation> = listSavedLocationsResult

        override suspend fun deleteSavedLocation(
            userId: Int,
            savedLocationId: Int,
        ): AppResult<Unit, SavedLocationError> = deleteSavedLocationResult
    }

    @Test
    fun `creating a saved location twice with the same idempotency key does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSavedLocationService()
            application {
                installTestModules()
                routing { apiV1 { savedLocationRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")
            val body = """{"label":"Home","latitude":47.4979,"longitude":19.0402,"radiusKm":2.5}"""

            val first =
                postJson("/api/v1/saved-locations", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "saved-location-key-1")
                }
            val second =
                postJson("/api/v1/saved-locations", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "saved-location-key-1")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals("true", second.headers["X-Idempotency-Replayed"])
            assertEquals(1, service.createSavedLocationCallCount, "the handler must not run a second time on replay")
        }

    @Test
    fun `an out-of-range latitude is rejected before reaching the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSavedLocationService()
            application {
                installTestModules()
                routing { apiV1 { savedLocationRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")
            val body = """{"label":"Home","latitude":190.0,"longitude":19.0402,"radiusKm":2.5}"""

            val response = postJson("/api/v1/saved-locations", body) { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.createSavedLocationCallCount)
        }

    @Test
    fun `a radius over the max is rejected before reaching the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSavedLocationService()
            application {
                installTestModules()
                routing { apiV1 { savedLocationRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")
            val body = """{"label":"Home","latitude":47.4979,"longitude":19.0402,"radiusKm":500.0}"""

            val response = postJson("/api/v1/saved-locations", body) { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.createSavedLocationCallCount)
        }

    @Test
    fun `listing saved locations returns the service's list`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSavedLocationService()
            application {
                installTestModules()
                routing { apiV1 { savedLocationRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.get("/api/v1/saved-locations") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `deleting a saved location returns no content on success`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing { apiV1 { savedLocationRoutes(FakeSavedLocationService(), FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.delete("/api/v1/saved-locations/1") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `deleting someone else's saved location returns not found`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeSavedLocationService()
            service.deleteSavedLocationResult = AppResult.Error(SavedLocationError.NOT_FOUND)
            application {
                installTestModules()
                routing { apiV1 { savedLocationRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.delete("/api/v1/saved-locations/1") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
