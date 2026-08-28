package com.shelflife.feature.pantryEntry.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.domain.AppResult
import com.shelflife.core.testAccessTokenFor
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome
import com.shelflife.feature.pantryEntry.routing.dto.response.PantryEntryPageResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PantryEntryRoutesTest {
    @Test
    fun `listing requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryRoutesTestApp() }

            val response = client.get(PantryEntryPaths.LIST)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `listing returns the service's entries as json`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryService().apply {
                    pageResult =
                        PantryEntryPage(items = listOf(FakePantryEntryService.sampleEntry()), nextCursor = null)
                }
            application { installPantryEntryRoutesTestApp(service) }

            val response = client.get(PantryEntryPaths.LIST) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<PantryEntryPageResponseDto>(response.bodyAsText())
            assertEquals(1, body.items.size)
            assertEquals(null, body.nextCursor)
        }

    @Test
    fun `a next page cursor is included when there's more to fetch`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryService().apply {
                    pageResult = PantryEntryPage(items = listOf(FakePantryEntryService.sampleEntry()), nextCursor = 1)
                }
            application { installPantryEntryRoutesTestApp(service) }

            val response = client.get(PantryEntryPaths.LIST) { bearerAuth(testAccessTokenFor(userId = 1)) }

            val body = Json.decodeFromString<PantryEntryPageResponseDto>(response.bodyAsText())
            assertEquals(1, body.nextCursor)
        }

    @Test
    fun `a non-numeric afterId is treated as absent, not an error`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                client.get("${PantryEntryPaths.LIST}?afterId=not-a-number") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(null, service.lastListCall?.first)
        }

    @Test
    fun `the afterId and limit query parameters are passed through to the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            client.get("${PantryEntryPaths.LIST}?afterId=5&limit=10") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(5 to 10, service.lastListCall)
        }

    @Test
    fun `getting a single entry by id requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryRoutesTestApp() }

            val response = client.get(PantryEntryPaths.byId(1))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `getting a single entry by id returns it as json`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService().apply { findByIdResult = FakePantryEntryService.sampleEntry() }
            application { installPantryEntryRoutesTestApp(service) }

            val response = client.get(PantryEntryPaths.byId(1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `getting a single entry that doesn't exist or isn't visible returns 404`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService().apply { findByIdResult = null }
            application { installPantryEntryRoutesTestApp(service) }

            val response = client.get(PantryEntryPaths.byId(999)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", response.errorBody().error)
        }

    @Test
    fun `getting a single entry with a non-numeric id returns 400`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryRoutesTestApp() }

            val response =
                client.get(PantryEntryPaths.byId(999).replace("999", "not-a-number")) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_ENTRY_ID", response.errorBody().error)
        }

    @Test
    fun `creating an entry returns 201 with the created entry`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                postJson(PantryEntryPaths.CREATE, """{"productId":1,"unitId":1,"quantityAmount":1.5}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(ProductReference.Existing(1), service.lastCreateCall?.product)
        }

    @Test
    fun `creating an entry with a new product name returns 201 and passes it through as ProductReference-New`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                postJson(
                    PantryEntryPaths.CREATE,
                    """{"newProductName":"Sertéshús","unitId":1,"quantityAmount":1.0}""",
                ) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(ProductReference.New("Sertéshús"), service.lastCreateCall?.product)
        }

    @Test
    fun `a new product name colliding with an existing one returns 409`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryService().apply {
                    createResult = AppResult.Error(PantryEntryError.PRODUCT_NAME_ALREADY_EXISTS)
                }
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                postJson(PantryEntryPaths.CREATE, """{"newProductName":"Sonka","unitId":1,"quantityAmount":1.0}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("PRODUCT_NAME_ALREADY_EXISTS", response.errorBody().error)
        }

    @Test
    fun `providing both productId and newProductName fails DTO validation before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                postJson(
                    PantryEntryPaths.CREATE,
                    """{"productId":1,"newProductName":"Sonka","unitId":1,"quantityAmount":1.0}""",
                ) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.createCallCount)
        }

    @Test
    fun `providing neither productId nor newProductName fails DTO validation before the service is called`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                postJson(PantryEntryPaths.CREATE, """{"unitId":1,"quantityAmount":1.0}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.createCallCount)
        }

    @Test
    fun `creating an entry requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryRoutesTestApp() }

            val response = postJson(PantryEntryPaths.CREATE, """{"productId":1,"unitId":1,"quantityAmount":1.5}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a zero quantity on create fails DTO validation before the service is even called`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                postJson(PantryEntryPaths.CREATE, """{"productId":1,"unitId":1,"quantityAmount":0}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, service.createCallCount)
        }

    @Test
    fun `a product not visible to the caller returns 400`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryService().apply {
                    createResult =
                        AppResult.Error(PantryEntryError.PRODUCT_NOT_VISIBLE)
                }
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                postJson(PantryEntryPaths.CREATE, """{"productId":1,"unitId":1,"quantityAmount":1.5}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("PRODUCT_NOT_VISIBLE", response.errorBody().error)
        }

    @Test
    fun `creating twice with the same idempotency key does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }
            val body = """{"productId":1,"unitId":1,"quantityAmount":1.5}"""

            val first =
                postJson(PantryEntryPaths.CREATE, body) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header("Idempotency-Key", "entry-key-1")
                }
            val second =
                postJson(PantryEntryPaths.CREATE, body) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header("Idempotency-Key", "entry-key-1")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals(1, service.createCallCount)
        }

    @Test
    fun `updating an entry that results in Updated returns 200`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryService().apply {
                    updateResult = AppResult.Success(UpdateEntryOutcome.Updated(FakePantryEntryService.sampleEntry()))
                }
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                patchJson(PantryEntryPaths.byId(1), """{"unitId":1,"quantityAmount":2.0}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `updating an entry that results in Deleted returns 204`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakePantryEntryService().apply {
                    updateResult =
                        AppResult.Success(
                            UpdateEntryOutcome.Deleted,
                        )
                }
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                patchJson(PantryEntryPaths.byId(1), """{"unitId":1,"quantityAmount":0}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `updating a nonexistent entry returns 404`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService().apply { updateResult = AppResult.Error(PantryEntryError.NOT_FOUND) }
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                patchJson(PantryEntryPaths.byId(999), """{"unitId":1,"quantityAmount":1.0}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `an invalid entry id in the patch path returns 400`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response =
                patchJson(PantryEntryPaths.byId(999).replace("999", "abc"), """{"unitId":1,"quantityAmount":1.0}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_ENTRY_ID", response.errorBody().error)
        }

    @Test
    fun `patching twice with the same body is not treated as a duplicate idempotency replay`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }
            val body = """{"unitId":1,"quantityAmount":2.0}"""

            patchJson(PantryEntryPaths.byId(1), body) { bearerAuth(testAccessTokenFor(userId = 1)) }
            patchJson(PantryEntryPaths.byId(1), body) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(2, service.updateCallCount, "PATCH is naturally idempotent, not wrapped in idempotent {}")
        }

    @Test
    fun `deleting an entry returns 204`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService()
            application { installPantryEntryRoutesTestApp(service) }

            val response = deleteRequest(PantryEntryPaths.byId(1)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `deleting a nonexistent entry returns 404`() =
        testApplication {
            configureTestEnvironment()
            val service = FakePantryEntryService().apply { deleteResult = AppResult.Error(PantryEntryError.NOT_FOUND) }
            application { installPantryEntryRoutesTestApp(service) }

            val response = deleteRequest(PantryEntryPaths.byId(999)) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `deleting requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installPantryEntryRoutesTestApp() }

            val response = deleteRequest(PantryEntryPaths.byId(1))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
