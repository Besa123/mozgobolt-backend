/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.companyFavorite.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.companyFavorite.routing.dto.response.CompanyFavoriteResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanyFavoriteRoutesTest {
    private class FakeCompanyFavoriteService : CompanyFavoriteService {
        var favoriteCallCount = 0
            private set
        var lastFavoriteCall: Pair<Int, Int>? = null
            private set
        var unfavoriteCallCount = 0
            private set
        var lastUnfavoriteCall: Pair<Int, Int>? = null
            private set
        var listFavoritesCallCount = 0
            private set
        var lastListFavoritesUserId: Int? = null
            private set
        var favoriteResult: AppResult<Unit, CompanyFavoriteError> = AppResult.Success(Unit)
        var unfavoriteResult: AppResult<Unit, CompanyFavoriteError> = AppResult.Success(Unit)
        var listFavoritesResult: List<CompanyFavorite> = emptyList()

        override suspend fun favoriteCompany(
            userId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyFavoriteError> {
            favoriteCallCount++
            lastFavoriteCall = userId to companyId
            return favoriteResult
        }

        override suspend fun unfavoriteCompany(
            userId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyFavoriteError> {
            unfavoriteCallCount++
            lastUnfavoriteCall = userId to companyId
            return unfavoriteResult
        }

        override suspend fun listFavorites(userId: Int): List<CompanyFavorite> {
            listFavoritesCallCount++
            lastListFavoritesUserId = userId
            return listFavoritesResult
        }
    }

    @Test
    fun `favoriting a company returns no content on success`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyFavoriteService()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.post("/api/v1/companies/1/favorite") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(1 to 1, service.lastFavoriteCall)
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
            val service = FakeCompanyFavoriteService()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "BUYER")

            val response = client.delete("/api/v1/companies/1/favorite") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(1 to 1, service.lastUnfavoriteCall)
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
            assertEquals(
                listOf(CompanyFavoriteResponseDto(companyId = 42)),
                Json.decodeFromString<List<CompanyFavoriteResponseDto>>(response.bodyAsText()),
            )
            assertEquals(1, service.lastListFavoritesUserId)
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

    @Test
    fun `unfavoriting a missing company maps to not found`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyFavoriteService()
            service.unfavoriteResult = AppResult.Error(CompanyFavoriteError.COMPANY_NOT_FOUND)
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(service) } }
            }

            val response =
                client.delete("/api/v1/companies/999/favorite") {
                    bearerAuth(testAccessTokenFor(userId = 1))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(1, service.unfavoriteCallCount)
        }

    @Test
    fun `invalid company ids are rejected on both favorite mutation routes`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyFavoriteService()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(service) } }
            }
            val token = testAccessTokenFor(userId = 1)

            val favorite =
                client.post("/api/v1/companies/not-a-number/favorite") {
                    bearerAuth(token)
                }
            val unfavorite =
                client.delete("/api/v1/companies/not-a-number/favorite") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.BadRequest, favorite.status)
            assertEquals(HttpStatusCode.BadRequest, unfavorite.status)
            assertEquals(0, service.favoriteCallCount)
            assertEquals(0, service.unfavoriteCallCount)
        }

    @Test
    fun `listing favorites without authentication is unauthorized`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyFavoriteService()
            application {
                installTestModules()
                routing { apiV1 { companyFavoriteRoutes(service) } }
            }

            val response = client.get("/api/v1/favorites")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, service.listFavoritesCallCount)
        }
}
