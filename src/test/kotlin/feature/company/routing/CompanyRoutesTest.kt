/*
 * SPDX-License-Identifier: Apache-2.0
 */

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
import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.company.routing.dto.response.CompanyMembershipResponseDto
import com.mozgobolt.feature.company.routing.dto.response.CompanyResponseDto
import com.mozgobolt.feature.company.routing.dto.response.InviteCodeResponseDto
import com.mozgobolt.feature.company.routing.dto.response.MyCompanyResponseDto
import com.mozgobolt.feature.user.routing.patchJson
import com.mozgobolt.feature.user.routing.postJson
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanyRoutesTest {
    private class FakeCompanyService : CompanyService {
        var listCompaniesForUserResult: AppResult<List<CompanyMembershipSummary>, CompanyError> =
            AppResult.Success(emptyList())
        val listCompaniesForUserCalls = mutableListOf<Int>()

        var listMembersResult: AppResult<List<CompanyMemberSummary>, CompanyError> =
            AppResult.Success(emptyList())
        var lastListMembersCall: Pair<Int, Int>? = null
        var joinCompanyCallCount = 0
            private set
        var joinCompanyResult: AppResult<Company, CompanyError> =
            AppResult.Success(
                Company(
                    id = 1,
                    name = "FamilyFrost",
                    inviteCode = "secret123",
                    createdAt = Instant.now(),
                ),
            )

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
        var renameCompanyResult: AppResult<Company, CompanyError> =
            AppResult.Success(
                Company(
                    id = 1,
                    name = "Renamed Company",
                    inviteCode = "abc123",
                    createdAt = Instant.now(),
                ),
            )
        var lastRenameCompanyCall: Triple<Int, Int, String>? = null
            private set
        var deleteCompanyResult: AppResult<Unit, CompanyError> = AppResult.Success(Unit)
        var lastDeleteCompanyCall: Pair<Int, Int>? = null
            private set
        var promoteMemberResult: AppResult<Unit, CompanyError> = AppResult.Success(Unit)
        var lastPromoteMemberCall: Triple<Int, Int, Int>? = null
            private set
        var demoteMemberResult: AppResult<Unit, CompanyError> = AppResult.Success(Unit)
        var lastDemoteMemberCall: Triple<Int, Int, Int>? = null
            private set
        var removeMemberResult: AppResult<Unit, CompanyError> = AppResult.Success(Unit)
        var lastRemoveMemberCall: Triple<Int, Int, Int>? = null
            private set
        var leaveCompanyResult: AppResult<Unit, CompanyError> = AppResult.Success(Unit)
        var lastLeaveCompanyCall: Pair<Int, Int>? = null
            private set

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
        var currentInviteCodeResult: AppResult<String, CompanyError> = AppResult.Success("current1")
        var lastCurrentInviteCodeCall: Pair<Int, Int>? = null

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
        ): AppResult<Company, CompanyError> {
            joinCompanyCallCount++
            return joinCompanyResult
        }

        override suspend fun renameCompany(
            adminUserId: Int,
            companyId: Int,
            newName: String,
        ): AppResult<Company, CompanyError> {
            lastRenameCompanyCall = Triple(adminUserId, companyId, newName)
            return renameCompanyResult
        }

        override suspend fun deleteCompany(
            adminUserId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyError> {
            lastDeleteCompanyCall = adminUserId to companyId
            return deleteCompanyResult
        }

        override suspend fun regenerateInviteCode(
            adminUserId: Int,
            companyId: Int,
        ): AppResult<Company, CompanyError> {
            regenerateInviteCodeCallCount++
            return regenerateInviteCodeResult
        }

        override suspend fun getCurrentInviteCode(
            adminUserId: Int,
            companyId: Int,
        ): AppResult<String, CompanyError> {
            lastCurrentInviteCodeCall = adminUserId to companyId
            return currentInviteCodeResult
        }

        override suspend fun listMembers(
            userId: Int,
            companyId: Int,
        ): AppResult<List<CompanyMemberSummary>, CompanyError> {
            lastListMembersCall = userId to companyId
            return listMembersResult
        }

        override suspend fun listCompaniesForUser(
            userId: Int,
        ): AppResult<List<CompanyMembershipSummary>, CompanyError> {
            listCompaniesForUserCalls += userId
            return listCompaniesForUserResult
        }

        override suspend fun promoteMember(
            adminUserId: Int,
            companyId: Int,
            targetUserId: Int,
        ): AppResult<Unit, CompanyError> {
            lastPromoteMemberCall = Triple(adminUserId, companyId, targetUserId)
            return promoteMemberResult
        }

        override suspend fun demoteMember(
            adminUserId: Int,
            companyId: Int,
            targetUserId: Int,
        ): AppResult<Unit, CompanyError> {
            lastDemoteMemberCall = Triple(adminUserId, companyId, targetUserId)
            return demoteMemberResult
        }

        override suspend fun removeMember(
            adminUserId: Int,
            companyId: Int,
            targetUserId: Int,
        ): AppResult<Unit, CompanyError> {
            lastRemoveMemberCall = Triple(adminUserId, companyId, targetUserId)
            return removeMemberResult
        }

        override suspend fun leaveCompany(
            userId: Int,
            companyId: Int,
        ): AppResult<Unit, CompanyError> {
            lastLeaveCompanyCall = userId to companyId
            return leaveCompanyResult
        }
    }

    @Test
    fun `joining a company replays the result and never returns its invite code to a member`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            val idempotencyStore = FakeIdempotencyStore()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, idempotencyStore) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "VENDOR")
            val body = """{"inviteCode":"join-code"}"""

            val first =
                postJson("/api/v1/companies/join", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "join-key-1")
                }
            val replay =
                postJson("/api/v1/companies/join", body) {
                    bearerAuth(token)
                    header("Idempotency-Key", "join-key-1")
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["X-Idempotency-Replayed"])
            assertEquals(1, service.joinCompanyCallCount)
            assertEquals(null, Json.decodeFromString<CompanyResponseDto>(first.bodyAsText()).inviteCode)
            assertEquals(null, Json.decodeFromString<CompanyResponseDto>(replay.bodyAsText()).inviteCode)
            assertEquals(false, first.bodyAsText().contains("secret123"))
        }

    @Test
    fun `company member list includes each member's name and role`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            service.listMembersResult =
                AppResult.Success(
                    listOf(
                        CompanyMemberSummary(userId = 10, name = "Alex Vendor", role = CompanyRole.ADMIN),
                        CompanyMemberSummary(userId = 11, name = "Sam Vendor", role = CompanyRole.MEMBER),
                    ),
                )
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }

            val response =
                client.get("/api/v1/companies/5/members") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                listOf(
                    CompanyMembershipResponseDto(10, "Alex Vendor", "ADMIN"),
                    CompanyMembershipResponseDto(11, "Sam Vendor", "MEMBER"),
                ),
                Json.decodeFromString<List<CompanyMembershipResponseDto>>(response.bodyAsText()),
            )
        }

    @Test
    fun `listing companies returns the caller's memberships without invite codes`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            service.listCompaniesForUserResult =
                AppResult.Success(
                    listOf(
                        CompanyMembershipSummary(
                            companyId = 3,
                            companyName = "First Company",
                            membershipRole = CompanyRole.ADMIN,
                        ),
                        CompanyMembershipSummary(
                            companyId = 8,
                            companyName = "Second Company",
                            membershipRole = CompanyRole.MEMBER,
                        ),
                    ),
                )
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }

            val response =
                client.get("/api/v1/companies") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                listOf(
                    MyCompanyResponseDto(3, "First Company", "ADMIN"),
                    MyCompanyResponseDto(8, "Second Company", "MEMBER"),
                ),
                Json.decodeFromString<List<MyCompanyResponseDto>>(response.bodyAsText()),
            )
            assertEquals(listOf(42), service.listCompaniesForUserCalls)
        }

    @Test
    fun `listing companies for a vendor with no memberships returns an empty array`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }

            val response =
                client.get("/api/v1/companies") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(emptyList(), Json.decodeFromString<List<MyCompanyResponseDto>>(response.bodyAsText()))
        }

    @Test
    fun `listing companies is vendor-only`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }

            val response =
                client.get("/api/v1/companies") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "BUYER"))
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(emptyList(), service.listCompaniesForUserCalls)
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
    fun `reading the current invite code returns only the code for the company admin`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            service.currentInviteCodeResult = AppResult.Success("current1")
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }

            val response =
                client.get("/api/v1/companies/7/invite-code") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                InviteCodeResponseDto("current1"),
                Json.decodeFromString<InviteCodeResponseDto>(response.bodyAsText()),
            )
            assertEquals(42 to 7, service.lastCurrentInviteCodeCall)
            assertEquals("no-store", response.headers["Cache-Control"])
        }

    @Test
    fun `reading the current invite code maps a non-admin to forbidden`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            service.currentInviteCodeResult = AppResult.Error(CompanyError.NOT_ADMIN)
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }

            val response =
                client.get("/api/v1/companies/7/invite-code") {
                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
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

    @Test
    fun `company and membership mutation routes forward ids and return success statuses`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "VENDOR")

            val renamed =
                patchJson("/api/v1/companies/7", """{"name":"Renamed Company"}""") {
                    bearerAuth(token)
                }
            val deleted = client.delete("/api/v1/companies/7") { bearerAuth(token) }
            val promoted = client.post("/api/v1/companies/7/members/9/promote") { bearerAuth(token) }
            val demoted = client.post("/api/v1/companies/7/members/10/demote") { bearerAuth(token) }
            val removed = client.delete("/api/v1/companies/7/members/11") { bearerAuth(token) }
            val left = client.delete("/api/v1/companies/7/members/me") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, renamed.status)
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(HttpStatusCode.OK, promoted.status)
            assertEquals(HttpStatusCode.OK, demoted.status)
            assertEquals(HttpStatusCode.NoContent, removed.status)
            assertEquals(HttpStatusCode.NoContent, left.status)
            assertEquals(Triple(42, 7, "Renamed Company"), service.lastRenameCompanyCall)
            assertEquals(42 to 7, service.lastDeleteCompanyCall)
            assertEquals(Triple(42, 7, 9), service.lastPromoteMemberCall)
            assertEquals(Triple(42, 7, 10), service.lastDemoteMemberCall)
            assertEquals(Triple(42, 7, 11), service.lastRemoveMemberCall)
            assertEquals(42 to 7, service.lastLeaveCompanyCall)
        }

    @Test
    fun `company routes map each domain error to its public HTTP status`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "VENDOR")
            service.createCompanyResult = AppResult.Error(CompanyError.ALREADY_MEMBER)
            service.joinCompanyResult = AppResult.Error(CompanyError.INVALID_INVITE_CODE)
            service.renameCompanyResult = AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            service.regenerateInviteCodeResult = AppResult.Error(CompanyError.NOT_ADMIN)
            service.deleteCompanyResult = AppResult.Error(CompanyError.NOT_A_MEMBER)
            service.listMembersResult = AppResult.Error(CompanyError.TARGET_NOT_A_MEMBER)
            service.demoteMemberResult = AppResult.Error(CompanyError.LAST_ADMIN)

            val create =
                postJson("/api/v1/companies", """{"name":"New Company"}""") { bearerAuth(token) }
            val join =
                postJson("/api/v1/companies/join", """{"inviteCode":"invalid"}""") { bearerAuth(token) }
            val rename =
                patchJson("/api/v1/companies/1", """{"name":"Renamed"}""") { bearerAuth(token) }
            val regenerate = client.post("/api/v1/companies/1/invite-code/regenerate") { bearerAuth(token) }
            val delete = client.delete("/api/v1/companies/1") { bearerAuth(token) }
            val members = client.get("/api/v1/companies/1/members") { bearerAuth(token) }
            val demote = client.post("/api/v1/companies/1/members/2/demote") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Conflict, create.status)
            assertEquals(HttpStatusCode.BadRequest, join.status)
            assertEquals(HttpStatusCode.NotFound, rename.status)
            assertEquals(HttpStatusCode.Forbidden, regenerate.status)
            assertEquals(HttpStatusCode.Forbidden, delete.status)
            assertEquals(HttpStatusCode.NotFound, members.status)
            assertEquals(HttpStatusCode.Conflict, demote.status)
        }

    @Test
    fun `invalid company and member path ids are rejected before calling services`() =
        testApplication {
            configureTestEnvironment()
            val service = FakeCompanyService()
            application {
                installTestModules()
                routing { apiV1 { companyRoutes(service, FakeIdempotencyStore()) } }
            }
            val token = testAccessTokenFor(userId = 42, role = "VENDOR")

            val members = client.get("/api/v1/companies/not-an-id/members") { bearerAuth(token) }
            val rename =
                patchJson("/api/v1/companies/not-an-id", """{"name":"Renamed"}""") { bearerAuth(token) }
            val regenerate =
                client.post("/api/v1/companies/not-an-id/invite-code/regenerate") { bearerAuth(token) }
            val delete = client.delete("/api/v1/companies/not-an-id") { bearerAuth(token) }
            val promote = client.post("/api/v1/companies/1/members/not-an-id/promote") { bearerAuth(token) }
            val remove = client.delete("/api/v1/companies/1/members/not-an-id") { bearerAuth(token) }
            val leave = client.delete("/api/v1/companies/not-an-id/members/me") { bearerAuth(token) }

            listOf(members, rename, regenerate, delete, promote, remove, leave).forEach { response ->
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
            assertEquals(null, service.lastListMembersCall)
            assertEquals(null, service.lastRenameCompanyCall)
            assertEquals(null, service.lastDeleteCompanyCall)
            assertEquals(null, service.lastPromoteMemberCall)
            assertEquals(null, service.lastRemoveMemberCall)
            assertEquals(null, service.lastLeaveCompanyCall)
            assertEquals(0, service.regenerateInviteCodeCallCount)
        }
}
