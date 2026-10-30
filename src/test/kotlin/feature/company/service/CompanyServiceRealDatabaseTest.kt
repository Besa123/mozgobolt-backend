package com.mozgobolt.feature.company.service

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.company.withRealCompanyDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The unit tests in [CompanyServiceITest] prove the *service* correctly reacts to
 * [com.mozgobolt.feature.company.domain.CompanyMembershipRepository.addIfAbsent] returning null;
 * this proves the underlying assumption those tests are built on — that a real, concurrent
 * Postgres workload actually produces that outcome via the `UNIQUE(company_id, user_id)` index —
 * rather than trusting it by reasoning alone. Exposed throws outside a transaction in ways
 * invisible to fakes; this is only caught by a real DB (see CLAUDE.md's own note on exactly this
 * class of bug).
 */
class CompanyServiceRealDatabaseTest {
    @Test
    fun `company member list includes each member's display name and role`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "FamilyFrost").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            val members =
                harness.service.listMembers(userId = 1, companyId = company.id).fold(
                    onSuccess = { it },
                    onError = { error("member listing failed: $it") },
                )

            assertEquals(
                listOf(
                    CompanyMemberSummary(1, "Test User One", CompanyRole.ADMIN),
                    CompanyMemberSummary(2, "Test User Two", CompanyRole.MEMBER),
                ),
                members,
            )
        }
    }

    @Test
    fun `deleting a company cascades its memberships but preserves other memberships for the same user`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val companyToDelete =
                harness.service.createCompany(creatorUserId = 1, name = "Deleted Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            val survivingCompany =
                harness.service.createCompany(creatorUserId = 2, name = "Surviving Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 1, inviteCode = survivingCompany.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            harness.service.deleteCompany(adminUserId = 1, companyId = companyToDelete.id).fold(
                onSuccess = {},
                onError = { error("delete failed: $it") },
            )

            assertNull(harness.tx.transactional { harness.membershipRepository.find(companyToDelete.id, 1) })
            assertNotNull(harness.tx.transactional { harness.membershipRepository.find(survivingCompany.id, 1) })
            assertEquals(
                listOf(
                    CompanyMembershipSummary(
                        companyId = survivingCompany.id,
                        companyName = "Surviving Company",
                        membershipRole = CompanyRole.MEMBER,
                    ),
                ),
                harness.service.listCompaniesForUser(1).fold(
                    onSuccess = { it },
                    onError = { error("company listing failed: $it") },
                ),
            )
        }
    }

    @Test
    fun `listing companies reads the user's memberships and roles from the database`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val adminCompany =
                harness.service.createCompany(creatorUserId = 1, name = "Admin Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            val memberCompany =
                harness.service.createCompany(creatorUserId = 2, name = "Member Company").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 1, inviteCode = memberCompany.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )

            val companies =
                harness.service.listCompaniesForUser(1).fold(
                    onSuccess = { it },
                    onError = { error("company listing failed: $it") },
                )

            assertEquals(
                listOf(
                    CompanyMembershipSummary(adminCompany.id, "Admin Company", CompanyRole.ADMIN),
                    CompanyMembershipSummary(memberCompany.id, "Member Company", CompanyRole.MEMBER),
                ),
                companies,
            )
        }
    }

    @Test
    fun `two concurrent joins with the same invite code for the same user only ever produce one winner`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "FamilyFrost").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )

            val results =
                coroutineScope {
                    val first = async { harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode) }
                    val second = async { harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode) }
                    listOf(first.await(), second.await())
                }

            val successes = results.count { it is AppResult.Success }
            val alreadyMember =
                results.count { it is AppResult.Error && it.errorType == CompanyError.ALREADY_MEMBER }
            assertEquals(1, successes, "exactly one concurrent join should win: $results")
            assertEquals(1, alreadyMember, "the loser must see a clean ALREADY_MEMBER, not crash: $results")

            val membership = harness.tx.transactional { harness.membershipRepository.find(company.id, 2) }
            assertNotNull(membership, "the user must end up a member exactly once, regardless of which call won")
        }
    }

    @Test
    fun `two concurrent demotes of DIFFERENT admins on a two-admin company are both individually safe`() {
        // Documents actual behavior under real concurrency for the last-admin guard, which — per
        // CompanyServiceI.requireNotLastAdmin's own comment — is a plain read-then-check, not
        // row-locked: this is exactly the kind of race that must be observed for real, not assumed
        // away. Whatever Postgres actually does here (both succeed and leave zero admins, or one
        // loses to a timing effect) is the ground truth this test exists to pin down — if it ever
        // fails, that means the accepted race is real, not just theoretical, and needs revisiting.
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "FamilyFrost").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.tx.transactional { harness.membershipRepository.addIfAbsent(company.id, 2, CompanyRole.ADMIN) }

            coroutineScope {
                val first =
                    async { harness.service.demoteMember(adminUserId = 1, companyId = company.id, targetUserId = 1) }
                val second =
                    async { harness.service.demoteMember(adminUserId = 2, companyId = company.id, targetUserId = 2) }
                listOf(first.await(), second.await())
            }

            val remainingAdmins = harness.tx.transactional { harness.membershipRepository.countAdmins(company.id) }
            assertEquals(
                1,
                remainingAdmins,
                "a company must never end up with zero admins — if this fails, the accepted race in " +
                    "CompanyServiceI.requireNotLastAdmin is real, not just theoretical, and needs a stronger guard",
            )
        }
    }
}
