package com.mozgobolt.feature.company.routing

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.feature.company.domain.CompanyService
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.user.routing.postJson
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanyRoutesTest {
    private class FakeCompanyService : CompanyService {
        var createCompanyCallCount = 0
            private set
        var createCompanyResult: AppResult<Company, CompanyError> =
            AppResult.Success(
                Company(
                    id = 1,
                    name = "FamilyFrost",
                    inviteCode = "abc123",
                    createdAt = Instant.now(),
                ),
            )

        var regenerateInviteCodeCallCount = 0
            private set
        var regenerateInviteCodeResult: AppResult<Company, CompanyError> =
            AppResult.Success(
                Company(
                    id = 1,
                    name = "FamilyFrost",
                    inviteCode = "newcode1",
                    createdAt = Instant.now(),
                ),
            )

        override suspend fun createCompany(
            creatorUserId: Int,
            name: String,
        ): AppResult<Company, CompanyError> {
            createCompanyCallCount++
            return createCompanyResult
        }

        override suspend fun joinCompany(
            userId: Int,
            inviteCode: String,
        ): AppResult<Company, CompanyError> = error("not exercised by this test")

        override suspend fun renameCompany(
            adminUserId: Int,
            companyId: Int,
            newName: String,
        ): AppResult<Company, CompanyError> = error("not exercised by this test")

        override suspend fun deleteCompany(
            adminUserId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyError> = error("not exercised by this test")

        override suspend fun regenerateInviteCode(
            adminUserId: Int,
            companyId: Int,
        ): AppResult<Company, CompanyError> {
            regenerateInviteCodeCallCount++
            return regenerateInviteCodeResult
        }

        override suspend fun listMembers(
            userId: Int,
            companyId: Int,
        ): AppResult<List<CompanyMembership>, CompanyError> = error("not exercised by this test")

        override suspend fun promoteMember(
            adminUserId: Int,
            companyId: Int,
            targetUserId: Int,
        ): AppResult<Unit, CompanyError> = error("not exercised by this test")

        override suspend fun demoteMember(
            adminUserId: Int,
            companyId: Int,
            targetUserId: Int,
        ): AppResult<Unit, CompanyError> = error("not exercised by this test")

        override suspend fun removeMember(
            adminUserId: Int,
            companyId: Int,
            targetUserId: Int,
        ): AppResult<Unit, CompanyError> = error("not exercised by this test")

        override suspend fun leaveCompany(
            userId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyError> = error("not exercised by this test")
    }

    @Test
    fun `creating a company twice with the same idempotency key does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")
            val body = """{"name":"FamilyFrost"}"""

            val first =
                postJson("/api/v1/companies", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "company-key-1")
                }
            val second =
                postJson("/api/v1/companies", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "company-key-1")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals("true", second.headers["X-Idempotency-Replayed"])
            assertEquals(1, service.createCompanyCallCount, "the handler must not run a second time on replay")
        }

    @Test
    fun `creating a company without an idempotency key calls the service every time, as normal`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")
            val body = """{"name":"FamilyFrost"}"""

            postJson("/api/v1/companies", body) { bearerAuth(token) }
            postJson("/api/v1/companies", body) { bearerAuth(token) }

            assertEquals(2, service.createCompanyCallCount, "idempotency is opt-in via the header, never the default")
        }

    @Test
    fun `regenerating an invite code twice with the same idempotency key does not call the service twice`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val first =
                client.post("/api/v1/companies/1/invite-code/regenerate") {
                    bearerAuth(token)
                    header("Idempotency-Key", "regen-key-1")
                }
            val second =
                client.post("/api/v1/companies/1/invite-code/regenerate") {
                    bearerAuth(token)
                    header("Idempotency-Key", "regen-key-1")
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals("true", second.headers["X-Idempotency-Replayed"])
            assertEquals(
                1,
                service.regenerateInviteCodeCallCount,
                "a replayed retry must not mint a second, different code the caller never saw",
            )
        }

    @Test
    fun `regenerating an invite code without a key calls the service every time, as normal`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            client.post("/api/v1/companies/1/invite-code/regenerate") { bearerAuth(token) }
            client.post("/api/v1/companies/1/invite-code/regenerate") { bearerAuth(token) }

            assertEquals(
                2,
                service.regenerateInviteCodeCallCount,
                "idempotency is opt-in via the header, never the default",
            )
        }

    @Test
    fun `regenerating an invite code returns the new code from the service`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            service.regenerateInviteCodeResult =
                AppResult.Success(
                    Company(id = 1, name = "FamilyFrost", inviteCode = "brandnew", createdAt = Instant.now()),
                )
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response =
                client.post("/api/v1/companies/1/invite-code/regenerate") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(true, response.bodyAsText().contains("brandnew"))
        }

    @Test
    fun `regenerating an invite code as a non-admin is rejected`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            service.regenerateInviteCodeResult = AppResult.Error(CompanyError.NOT_ADMIN)
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response =
                client.post("/api/v1/companies/1/invite-code/regenerate") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `regenerating an invite code for an unknown company is rejected`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            service.regenerateInviteCodeResult = AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 1, role = "VENDOR")

            val response =
                client.post("/api/v1/companies/999/invite-code/regenerate") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
