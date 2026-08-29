package com.shelflife.feature.storageLocation.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.domain.AppResult
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import com.shelflife.feature.storageLocation.domain.model.StorageLocationError
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP route tests for storage locations covering:
 * - Happy paths for all CRUD operations
 * - Authorization (401 when not authenticated)
 * - Error responses (409 Conflict, 404 Not Found, etc.)
 * - Validation failures
 * - Body size limits
 */
class StorageLocationRoutesTest {
    // ===== GET /storage-locations =====

    @Test
    fun `get storage locations returns 200 with locations list`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    locations[1] =
                        mutableListOf(
                            StorageLocation(1, 1, "Fridge"),
                            StorageLocation(2, 1, "Freezer"),
                        )
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = getJson(StorageLocationPaths.LIST)

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, body.size)
            assertEquals("Fridge", body[0].jsonObject["name"]?.toString()?.trim('"'))
            assertEquals("Freezer", body[1].jsonObject["name"]?.toString()?.trim('"'))
        }

    @Test
    fun `get storage locations without auth returns 401`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = getJson(StorageLocationPaths.LIST, userId = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `get storage locations returns empty list when no locations exist`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = getJson(StorageLocationPaths.LIST)

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(0, body.size)
        }

    // ===== POST /storage-locations =====

    @Test
    fun `post storage locations with valid name returns 201 with location`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"Pantry"}""")

            assertEquals(HttpStatusCode.Created, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Pantry", body["name"]?.toString()?.trim('"'))
        }

    @Test
    fun `post storage locations without auth returns 401`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"Pantry"}""", userId = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `post storage locations with duplicate name returns 409 Conflict`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    createResult = AppResult.Error(StorageLocationError.DUPLICATE_NAME)
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"Duplicate"}""")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("DUPLICATE_NAME", response.errorBody().error)
        }

    @Test
    fun `post storage locations with empty name fails validation with 400`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":""}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
        }

    @Test
    fun `post storage locations with whitespace-only name fails validation with 400`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"   "}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `post storage locations with oversized body returns 413 PayloadTooLarge`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }
            // POST /storage-locations is registered with BodyLimit.SMALL (16 KB) — must clear that.
            val oversizedName = "a".repeat(20 * 1024)

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"$oversizedName"}""")

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `post storage locations with syntactically invalid json returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name": this is not valid json""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `post storage locations with a name over one hundred characters fails validation with 400`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }
            val tooLongName = "a".repeat(101)

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"$tooLongName"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
        }

    @Test
    fun `post storage locations with markup-like characters in the name fails validation with 400`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"<script>Fridge</script>"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
        }

    @Test
    fun `post storage locations with no name field fails validation with 400`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"some_field":"value"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ===== PATCH /storage-locations/{id} =====

    @Test
    fun `patch storage location with valid name returns 200 with updated location`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    renameResult = AppResult.Success(StorageLocation(1, 1, "NewName"))
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = patchJson(StorageLocationPaths.byId(1), """{"name":"NewName"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("NewName", body["name"]?.toString()?.trim('"'))
        }

    @Test
    fun `patch storage location without auth returns 401`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = patchJson(StorageLocationPaths.byId(1), """{"name":"NewName"}""", userId = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `patch storage location for non-existent location returns 404 NotFound`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    renameResult = AppResult.Error(StorageLocationError.NOT_FOUND)
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = patchJson(StorageLocationPaths.byId(9999), """{"name":"NewName"}""")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", response.errorBody().error)
        }

    @Test
    fun `patch storage location with duplicate name returns 409 Conflict`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    renameResult = AppResult.Error(StorageLocationError.DUPLICATE_NAME)
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = patchJson(StorageLocationPaths.byId(1), """{"name":"ExistingName"}""")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("DUPLICATE_NAME", response.errorBody().error)
        }

    @Test
    fun `patch storage location with invalid ID in path returns 400 BadRequest`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = patchJson(StorageLocationPaths.byId(999).replace("999", "invalid"), """{"name":"NewName"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_LOCATION_ID", response.errorBody().error)
        }

    @Test
    fun `patch storage location with empty name fails validation with 400`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = patchJson(StorageLocationPaths.byId(1), """{"name":""}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.errorBody().error)
        }

    // ===== DELETE /storage-locations/{id} =====

    @Test
    fun `delete storage location succeeds returns 204 NoContent`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    deleteResult = AppResult.Success(Unit)
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = deleteJson(StorageLocationPaths.byId(1))

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `delete storage location without auth returns 401`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = deleteJson(StorageLocationPaths.byId(1), userId = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `delete storage location for non-existent location returns 404 NotFound`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    deleteResult = AppResult.Error(StorageLocationError.NOT_FOUND)
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = deleteJson(StorageLocationPaths.byId(9999))

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", response.errorBody().error)
        }

    @Test
    fun `delete storage location with invalid ID in path returns 400 BadRequest`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = deleteJson(StorageLocationPaths.byId(999).replace("999", "notanid"))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_LOCATION_ID", response.errorBody().error)
        }

    // ===== EDGE CASES =====

    @Test
    fun `repeating a POST with the same Idempotency-Key replays the first response instead of creating twice`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response1 =
                postJson(StorageLocationPaths.CREATE, """{"name":"Unique"}""") {
                    header("Idempotency-Key", "retry-key-1")
                }
            val response2 =
                postJson(StorageLocationPaths.CREATE, """{"name":"Unique"}""") {
                    header("Idempotency-Key", "retry-key-1")
                }

            assertEquals(HttpStatusCode.Created, response1.status)
            assertEquals(HttpStatusCode.Created, response2.status)
            assertEquals(response1.bodyAsText(), response2.bodyAsText())
            assertEquals("true", response2.headers["X-Idempotency-Replayed"])
            // Only one row was actually created — the second call never reached the service.
            assertEquals(1, service.locations[1]?.size)
        }

    @Test
    fun `POSTing twice without an Idempotency-Key with a duplicate name returns 409 on the second call`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response1 = postJson(StorageLocationPaths.CREATE, """{"name":"Unique"}""")
            service.createResult = AppResult.Error(StorageLocationError.DUPLICATE_NAME)
            val response2 = postJson(StorageLocationPaths.CREATE, """{"name":"Unique"}""")

            assertEquals(HttpStatusCode.Created, response1.status)
            assertEquals(HttpStatusCode.Conflict, response2.status)
        }

    @Test
    fun `POST with very long valid name succeeds`() =
        testApplication {
            configureTestEnvironment()
            val longName = "A".repeat(100)
            val service = FakeStorageLocationService()
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"$longName"}""")

            assertEquals(HttpStatusCode.Created, response.status)
        }

    @Test
    fun `error responses have correct error field in JSON`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeStorageLocationService().apply {
                    createResult = AppResult.Error(StorageLocationError.DUPLICATE_NAME)
                }
            application { installStorageLocationRoutesTestApp(service) }

            val response = postJson(StorageLocationPaths.CREATE, """{"name":"Duplicate"}""")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains(""""error":"DUPLICATE_NAME""""))
        }
}
