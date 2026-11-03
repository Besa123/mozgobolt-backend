/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.service

import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import com.mozgobolt.feature.company.domain.model.CompanyRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class CompanyServiceListCompaniesTest {
    @Test
    fun `listing companies returns each membership and its current role`() =
        runBlocking {
            val fixture = CompanyServiceFixture()
            val adminCompany =
                fixture.service.createCompany(creatorUserId = 7, name = "Admin Company").fold(
                    onSuccess = { it },
                    onError = { fail("expected company creation to succeed, got $it") },
                )
            val memberCompany =
                fixture.service.createCompany(creatorUserId = 8, name = "Member Company").fold(
                    onSuccess = { it },
                    onError = { fail("expected company creation to succeed, got $it") },
                )
            fixture.service.joinCompany(userId = 7, inviteCode = memberCompany.inviteCode).fold(
                onSuccess = {},
                onError = { fail("expected company join to succeed, got $it") },
            )

            val companies =
                fixture.service.listCompaniesForUser(7).fold(
                    onSuccess = { it },
                    onError = { fail("expected company list to succeed, got $it") },
                )

            assertEquals(
                listOf(
                    CompanyMembershipSummary(
                        companyId = adminCompany.id,
                        companyName = "Admin Company",
                        membershipRole = CompanyRole.ADMIN,
                    ),
                    CompanyMembershipSummary(
                        companyId = memberCompany.id,
                        companyName = "Member Company",
                        membershipRole = CompanyRole.MEMBER,
                    ),
                ),
                companies,
            )
        }

    @Test
    fun `listing companies for a vendor with no memberships returns an empty list`() =
        runBlocking {
            val fixture = CompanyServiceFixture()

            val companies =
                fixture.service.listCompaniesForUser(99).fold(
                    onSuccess = { it },
                    onError = { fail("expected an empty company list, got $it") },
                )

            assertEquals(emptyList(), companies)
        }
}
