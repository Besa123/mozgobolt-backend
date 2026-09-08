package com.shelflife.feature.pantryEntryImage.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.domain.AppResult
import com.shelflife.core.testAccessTokenFor
import com.shelflife.feature.pantryEntry.routing.deleteRequest
import com.shelflife.feature.pantryEntry.routing.errorBody
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PantryEntryImageRoutesTest {
    @Test
    fun `uploading an image requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryImageRoutesTestApp() }

            val response = uploadImage(PantryEntryImagePaths.images(1))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `uploading an image returns 201 with the created image`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryImageService()
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                uploadImage(PantryEntryImagePaths.images(1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, service.addCallCount)
        }

    @Test
    fun `an invalid entry id on upload returns 400 without calling the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryImageService()
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                uploadImage(PantryEntryImagePaths.images(999).replace("999", "abc")) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_ENTRY_ID", response.errorBody().error)
            assertEquals(0, service.addCallCount)
        }

    @Test
    fun `too many images on an entry returns 409`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryImageService().apply {
                    addResult = AppResult.Error(PantryEntryImageError.TOO_MANY_IMAGES)
                }
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                uploadImage(PantryEntryImagePaths.images(1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("TOO_MANY_IMAGES", response.errorBody().error)
        }

    @Test
    fun `uploading twice with the same idempotency key and bytes does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryImageService()
            application { installPantryEntryImageRoutesTestApp(service) }

            val first =
                uploadImage(PantryEntryImagePaths.images(1)) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header("Idempotency-Key", "image-key-1")
                }
            val second =
                uploadImage(PantryEntryImagePaths.images(1)) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header("Idempotency-Key", "image-key-1")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals(1, service.addCallCount)
        }

    @Test
    fun `replacing an image returns 200`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryImageService()
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                replaceImage(PantryEntryImagePaths.imageById(1, 1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `replacing an image that doesn't exist returns 404`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryImageService().apply {
                    replaceResult = AppResult.Error(PantryEntryImageError.NOT_FOUND)
                }
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                replaceImage(PantryEntryImagePaths.imageById(1, 999)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `listing images requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryImageRoutesTestApp() }

            val response = client.get(PantryEntryImagePaths.images(1))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `listing images returns them as a json array`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryImageService().apply {
                    listResult = AppResult.Success(listOf(FakePantryEntryImageService.sampleImage()))
                }
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                client.get(PantryEntryImagePaths.images(1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()) as JsonArray
            assertEquals(1, body.size)
        }

    @Test
    fun `fetching image content returns the bytes with the right content type and an ETag`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryImageService()
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                client.get(PantryEntryImagePaths.content(1, 1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
            assertEquals(byteArrayOf(1, 2, 3).toList(), response.bodyAsBytes().toList())
            assertEquals("\"sample-etag\"", response.headers[HttpHeaders.ETag])
        }

    @Test
    fun `image content is cache-control no-cache, never a positive max-age`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryImageRoutesTestApp(FakePantryEntryImageService()) }

            val response =
                client.get(PantryEntryImagePaths.content(1, 1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            val cacheControl = response.headers[HttpHeaders.CacheControl].orEmpty()
            assertTrue(cacheControl.contains("no-cache"), "expected no-cache, got: $cacheControl")
            assertTrue(
                !Regex("max-age=[1-9]").containsMatchIn(cacheControl),
                "expected no positive max-age, got: $cacheControl",
            )
        }

    @Test
    fun `a matching If-None-Match returns 304 without the body`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryImageService()
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                client.get(PantryEntryImagePaths.content(1, 1)) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header(HttpHeaders.IfNoneMatch, "\"sample-etag\"")
                }

            assertEquals(HttpStatusCode.NotModified, response.status)
        }

    @Test
    fun `content for a missing image returns 404`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryImageService().apply {
                    loadContentResult = AppResult.Error(PantryEntryImageError.NOT_FOUND)
                }
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                client.get(PantryEntryImagePaths.content(1, 999)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `deleting an image returns 204`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryImageService()
            application { installPantryEntryImageRoutesTestApp(service) }

            val response =
                deleteRequest(PantryEntryImagePaths.imageById(1, 1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `deleting requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryImageRoutesTestApp() }

            val response = deleteRequest(PantryEntryImagePaths.imageById(1, 1))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
