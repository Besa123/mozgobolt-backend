package com.mozgobolt.feature.company.domain

import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.company.service.FakeCompanyMembershipRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private const val COMPANY_ID = 1
private const val USER_ID = 100

class MembershipCheckTest {
    @Test
    fun `not a member at all resolves to NotAMember`() =
        runBlocking {
            val repository = FakeCompanyMembershipRepository()

            val result = repository.requireAdminMembership(COMPANY_ID, USER_ID)

            assertEquals(MembershipCheck.NotAMember, result)
        }

    @Test
    fun `a member with role MEMBER resolves to NotAdmin`() =
        runBlocking {
            val repository = FakeCompanyMembershipRepository()
            repository.addIfAbsent(COMPANY_ID, USER_ID, CompanyRole.MEMBER)

            val result = repository.requireAdminMembership(COMPANY_ID, USER_ID)

            assertEquals(MembershipCheck.NotAdmin, result)
        }

    @Test
    fun `a member with role ADMIN resolves to Admin carrying the membership`() =
        runBlocking {
            val repository = FakeCompanyMembershipRepository()
            repository.addIfAbsent(COMPANY_ID, USER_ID, CompanyRole.ADMIN)

            val result = repository.requireAdminMembership(COMPANY_ID, USER_ID)

            check(result is MembershipCheck.Admin) { "expected Admin, got $result" }
            assertEquals(COMPANY_ID, result.membership.companyId)
            assertEquals(USER_ID, result.membership.userId)
            assertEquals(CompanyRole.ADMIN, result.membership.role)
        }

    @Test
    fun `membership in a different company does not count`() =
        runBlocking {
            val repository = FakeCompanyMembershipRepository()
            repository.addIfAbsent(companyId = 2, userId = USER_ID, role = CompanyRole.ADMIN)

            val result = repository.requireAdminMembership(COMPANY_ID, USER_ID)

            assertEquals(MembershipCheck.NotAMember, result)
        }
}
