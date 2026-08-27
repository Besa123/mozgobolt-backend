package com.shelflife.feature.product.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.domain.AppResult
import com.shelflife.core.testAccessTokenFor
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.product.domain.model.UnitCategory
import com.shelflife.feature.product.routing.dto.response.ProductResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductRoutesTest {
    @Test
    fun `search requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installProductRoutesTestApp() }

            val response = client.get("${ProductPaths.SEARCH}?q=son")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `search returns the service's matches as json`() =
        testApplication {
            configureTestEnvironment()
            val productService =
                FakeProductService().apply {
                    searchResult =
                        listOf(
                            Product(
                                id = 1,
                                name = "Sonka",
                                ownerId = null,
                                defaultLifespanDays = 5,
                                defaultUnitCategory = UnitCategory.MASS,
                            ),
                        )
                }
            application { installProductRoutesTestApp(productService) }

            val response =
                client.get("${ProductPaths.SEARCH}?q=son") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("son", productService.lastSearchQuery)
            val results = Json.decodeFromString<List<ProductResponseDto>>(response.bodyAsText())
            assertEquals("Sonka", results.single().name)
        }

    @Test
    fun `listing my products requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installProductRoutesTestApp() }

            val response = client.get(ProductPaths.MINE)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `listing my products returns the service's owned products as json`() =
        testApplication {
            configureTestEnvironment()
            val productService =
                FakeProductService().apply {
                    listOwnedByResult =
                        listOf(
                            Product(
                                id = 1,
                                name = "Sonka",
                                ownerId = 1,
                                defaultLifespanDays = null,
                                defaultUnitCategory = null,
                            ),
                        )
                }
            application { installProductRoutesTestApp(productService) }

            val response = client.get(ProductPaths.MINE) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            val results = Json.decodeFromString<List<ProductResponseDto>>(response.bodyAsText())
            assertEquals("Sonka", results.single().name)
        }

    @Test
    fun `creating a product returns 201 with the created product`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                postJson(ProductPaths.CREATE, """{"name":"Sonka"}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, productService.lastCreateCall?.userId)
            assertEquals("Sonka", productService.lastCreateCall?.name)
        }

    @Test
    fun `a duplicate name returns 409`() =
        testApplication {
            configureTestEnvironment()
            val productService =
                FakeProductService().apply { createResult = AppResult.Error(ProductError.DUPLICATE_NAME) }
            application { installProductRoutesTestApp(productService) }

            val response =
                postJson(ProductPaths.CREATE, """{"name":"Sonka"}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("DUPLICATE_NAME", response.errorBody().error)
        }

    @Test
    fun `a blank name fails DTO validation before the service is even called`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                postJson(ProductPaths.CREATE, """{"name":""}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, productService.createCallCount)
        }

    @Test
    fun `creating a product requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installProductRoutesTestApp() }

            val response = postJson(ProductPaths.CREATE, """{"name":"Sonka"}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `creating twice with the same idempotency key does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val first =
                postJson(ProductPaths.CREATE, """{"name":"Sonka"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header("Idempotency-Key", "prod-key-1")
                }
            val second =
                postJson(ProductPaths.CREATE, """{"name":"Sonka"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header("Idempotency-Key", "prod-key-1")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals(1, productService.createCallCount)
        }

    @Test
    fun `reusing the same idempotency key with a different name is rejected`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            postJson(ProductPaths.CREATE, """{"name":"Sonka"}""") {
                bearerAuth(testAccessTokenFor(userId = 1))
                header("Idempotency-Key", "prod-key-2")
            }
            val second =
                postJson(ProductPaths.CREATE, """{"name":"Sajt"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    header("Idempotency-Key", "prod-key-2")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, second.status)
        }

    @Test
    fun `syntactically invalid json returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                postJson(ProductPaths.CREATE, """{"name": this is not valid json""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, productService.createCallCount)
        }

    @Test
    fun `a body sent with the wrong content type returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                client.post(ProductPaths.CREATE) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    contentType(ContentType.Text.Plain)
                    setBody("""{"name":"Sonka"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_BODY", response.errorBody().error)
        }

    @Test
    fun `a request with no body at all returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                client.post(ProductPaths.CREATE) {
                    bearerAuth(testAccessTokenFor(userId = 1))
                    contentType(ContentType.Application.Json)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a body over the 16KB create limit is rejected with 413`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }
            val oversizedName = "a".repeat(20 * 1024)

            val response =
                postJson(ProductPaths.CREATE, """{"name":"$oversizedName"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(0, productService.createCallCount)
        }

    @Test
    fun `an unrecognized unit category value returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                postJson(ProductPaths.CREATE, """{"name":"Sonka","defaultUnitCategory":"NOT_A_REAL_CATEGORY"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, productService.createCallCount)
        }

    @Test
    fun `a name with accented Hungarian characters is accepted`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                postJson(ProductPaths.CREATE, """{"name":"Vöröshagyma"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("Vöröshagyma", productService.lastCreateCall?.name)
        }

    @Test
    fun `a search with no query parameter at all is treated as an empty query, not a 400`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response = client.get(ProductPaths.SEARCH) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("", productService.lastSearchQuery)
        }

    @Test
    fun `renaming a product returns 200 with the renamed product`() =
        testApplication {
            configureTestEnvironment()
            val productService =
                FakeProductService().apply {
                    renameResult =
                        AppResult.Success(
                            Product(
                                id = 5,
                                name = "Sonka",
                                ownerId = 1,
                                defaultLifespanDays = null,
                                defaultUnitCategory = null,
                            ),
                        )
                }
            application { installProductRoutesTestApp(productService) }

            val response =
                patchJson(ProductPaths.rename(5), """{"name":"Sonka"}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, productService.lastRenameCall?.userId)
            assertEquals(5, productService.lastRenameCall?.productId)
            assertEquals("Sonka", productService.lastRenameCall?.newName)
        }

    @Test
    fun `renaming a product not owned by the caller returns 404`() =
        testApplication {
            configureTestEnvironment()
            val productService =
                FakeProductService().apply { renameResult = AppResult.Error(ProductError.NOT_FOUND) }
            application { installProductRoutesTestApp(productService) }

            val response =
                patchJson(ProductPaths.rename(5), """{"name":"Sonka"}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", response.errorBody().error)
        }

    @Test
    fun `renaming to a name that collides returns 409`() =
        testApplication {
            configureTestEnvironment()
            val productService =
                FakeProductService().apply { renameResult = AppResult.Error(ProductError.DUPLICATE_NAME) }
            application { installProductRoutesTestApp(productService) }

            val response =
                patchJson(ProductPaths.rename(5), """{"name":"Sonka"}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `a non-numeric product id returns 400 instead of a 500`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                patchJson(ProductPaths.rename(0).replace("0", "not-a-number"), """{"name":"Sonka"}""") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, productService.renameCallCount)
        }

    @Test
    fun `a blank new name fails DTO validation before the service is even called`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val response =
                patchJson(ProductPaths.rename(5), """{"name":""}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, productService.renameCallCount)
        }

    @Test
    fun `renaming requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installProductRoutesTestApp() }

            val response = patchJson(ProductPaths.rename(5), """{"name":"Sonka"}""")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `renaming twice with the same body is not blocked as a duplicate idempotency replay`() =
        testApplication {
            configureTestEnvironment()
            val productService = FakeProductService()
            application { installProductRoutesTestApp(productService) }

            val first =
                patchJson(ProductPaths.rename(5), """{"name":"Sonka"}""") { bearerAuth(testAccessTokenFor(userId = 1)) }
            val second =
                patchJson(ProductPaths.rename(5), """{"name":"Sonka"}""") { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(
                2,
                productService.renameCallCount,
                "rename is naturally idempotent, not wrapped in idempotent {}",
            )
        }
}
