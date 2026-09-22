package com.mozgobolt.feature.companyFavorite.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanyFavoriteRoutesTest {
    private class FakeCompanyFavoriteService : CompanyFavoriteService {
        var favoriteResult: AppResult<Unit, CompanyFavoriteError> = AppResult.Success(Unit)
        var unfavoriteResult: AppResult<Unit, CompanyFavoriteError> = AppResult.Success(Unit)
        var listFavoritesResult: List<CompanyFavorite> = emptyList()

        override suspend fun favoriteCompany(
            userId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyFavoriteError> = favoriteResult

        override suspend fun unfavoriteCompany(
            userId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyFavoriteError> = unfavoriteResult

        override suspend fun listFavorites(userId: Int): List<CompanyFavorite> = listFavoritesResult
    }

    @Test
    fun `favoriting a company returns no content on success`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(FakeCompanyFavoriteService()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.post("/api/v1/companies/1/favorite") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `favoriting a nonexistent company returns not found`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyFavoriteService()
            service.favoriteResult = AppResult.Error(CompanyFavoriteError.COMPANY_NOT_FOUND)
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.post("/api/v1/companies/999/favorite") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `favoriting without a token is unauthorized`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(FakeCompanyFavoriteService()) } }
            }

            val response = client.post("/api/v1/companies/1/favorite")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `unfavoriting a company returns no content`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(FakeCompanyFavoriteService()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.delete("/api/v1/companies/1/favorite") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `listing favorites returns the service's list`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyFavoriteService()
            service.listFavoritesResult =
                listOf(CompanyFavorite(id = 1, userId = 1, companyId = 42, createdAt = Instant.now()))
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.get("/api/v1/favorites") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `a vendor is also allowed to favorite a company — no role restriction beyond authentication`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(FakeCompanyFavoriteService()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response = client.post("/api/v1/companies/1/favorite") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }
}
