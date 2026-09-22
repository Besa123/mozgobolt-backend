package com.mozgobolt.feature.company.service

import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CompanyServiceCreateJoinTest {
    // --- createCompany() ---

    @Test
    fun `creating a company makes the creator its first admin`() {
        runBlocking {
            val fx = CompanyServiceFixture()

            val result = fx.service.createCompany(creatorUserId = 1, name = "FamilyFrost")

            val company = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals("FamilyFrost", company.name)
            assertEquals(CompanyRole.ADMIN, fx.membershipRepository.find(company.id, 1)?.role)
            assertTrue(fx.syncService.recorded.any { it.entityType == SyncEntityType.COMPANY })
            assertTrue(fx.syncService.recorded.any { it.entityType == SyncEntityType.COMPANY_MEMBERSHIP })
        }
    }

    @Test
    fun `the company name is trimmed before being stored`() {
        runBlocking {
            val fx = CompanyServiceFixture()

            val result = fx.service.createCompany(creatorUserId = 1, name = "  FamilyFrost  ")

            result.fold(onSuccess = { assertEquals("FamilyFrost", it.name) }, onError = { fail("$it") })
        }
    }

    @Test
    fun `two companies created back to back get different invite codes`() {
        runBlocking {
            val fx = CompanyServiceFixture()

            val companyA = fx.service.createCompany(1, "A").fold(onSuccess = { it }, onError = { fail("$it") })
            val companyB = fx.service.createCompany(2, "B").fold(onSuccess = { it }, onError = { fail("$it") })

            assertNotEquals(companyA.inviteCode, companyB.inviteCode)
        }
    }

    @Test
    fun `a user can create and be admin of multiple companies simultaneously`() {
        runBlocking {
            val fx = CompanyServiceFixture()

            val companyA = fx.service.createCompany(1, "A").fold(onSuccess = { it }, onError = { fail("$it") })
            val companyB = fx.service.createCompany(1, "B").fold(onSuccess = { it }, onError = { fail("$it") })

            assertEquals(CompanyRole.ADMIN, fx.membershipRepository.find(companyA.id, 1)?.role)
            assertEquals(CompanyRole.ADMIN, fx.membershipRepository.find(companyB.id, 1)?.role)
        }
    }

    // --- joinCompany() ---

    @Test
    fun `joining a company by invite code adds the user as a plain member`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.joinCompany(userId = 2, inviteCode = created.inviteCode)

            val joined = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(created.id, joined.id)
            assertEquals(CompanyRole.MEMBER, fx.membershipRepository.find(created.id, 2)?.role)
        }
    }

    @Test
    fun `joining with an unknown invite code is rejected`() {
        runBlocking {
            val fx = CompanyServiceFixture()

            val result = fx.service.joinCompany(userId = 2, inviteCode = "does-not-exist")

            result.fold(onSuccess = { fail("expected INVALID_INVITE_CODE but got success") }, onError = {
                assertEquals(CompanyError.INVALID_INVITE_CODE, it)
            })
        }
    }

    @Test
    fun `joining a company the user already belongs to is rejected, not duplicated`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.joinCompany(userId = 2, inviteCode = created.inviteCode)

            result.fold(onSuccess = { fail("expected ALREADY_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.ALREADY_MEMBER, it)
            })
            assertEquals(1, fx.membershipRepository.findAllForCompany(created.id).count { it.userId == 2 })
        }
    }

    @Test
    fun `losing the join race after the DB's unique index fires is reported as ALREADY_MEMBER, not a false success`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.membershipRepository.rejectNextAdd = true

            val result = fx.service.joinCompany(userId = 2, inviteCode = created.inviteCode)

            result.fold(onSuccess = { fail("expected ALREADY_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.ALREADY_MEMBER, it)
            })
        }
    }

    @Test
    fun `a user can be an admin of one company and a plain member of another simultaneously`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val companyA = fx.service.createCompany(1, "A").fold(onSuccess = { it }, onError = { fail("$it") })
            val companyB = fx.service.createCompany(2, "B").fold(onSuccess = { it }, onError = { fail("$it") })

            fx.service.joinCompany(1, companyB.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            assertEquals(CompanyRole.ADMIN, fx.membershipRepository.find(companyA.id, 1)?.role)
            assertEquals(CompanyRole.MEMBER, fx.membershipRepository.find(companyB.id, 1)?.role)
        }
    }
}
