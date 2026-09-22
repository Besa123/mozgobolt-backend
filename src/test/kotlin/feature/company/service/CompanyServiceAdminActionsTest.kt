package com.mozgobolt.feature.company.service

import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.fail

class CompanyServiceAdminActionsTest {
    // --- renameCompany() / deleteCompany() ---

    @Test
    fun `an admin can rename their company`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.renameCompany(adminUserId = 1, companyId = created.id, newName = "  New Name  ")

            result.fold(onSuccess = { assertEquals("New Name", it.name) }, onError = { fail("$it") })
        }
    }

    @Test
    fun `a plain member cannot rename the company`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.renameCompany(adminUserId = 2, companyId = created.id, newName = "Hijacked")

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(CompanyError.NOT_ADMIN, it)
            })
        }
    }

    @Test
    fun `a non-member cannot rename or delete a company`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            fx.service
                .renameCompany(
                    99,
                    created.id,
                    "Hijacked",
                ).fold(onSuccess = { fail("expected NOT_A_MEMBER") }, onError = {
                    assertEquals(CompanyError.NOT_A_MEMBER, it)
                })
            fx.service.deleteCompany(99, created.id).fold(onSuccess = { fail("expected NOT_A_MEMBER") }, onError = {
                assertEquals(CompanyError.NOT_A_MEMBER, it)
            })
        }
    }

    @Test
    fun `an admin can delete their company`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.deleteCompany(adminUserId = 1, companyId = created.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertNull(fx.companyRepository.findById(created.id))
        }
    }

    @Test
    fun `deleting a company clears any of its vehicles' active sessions off the live map`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            val vehicle = fx.vehicleRepository.seed(created.id)
            fx.assignmentRepository.seedActive(vehicle.id, vendorUserId = 1)

            val result = fx.service.deleteCompany(adminUserId = 1, companyId = created.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(listOf(vehicle.id), fx.locationHub.markedOffline)
        }
    }

    @Test
    fun `deleting an unknown company id is rejected cleanly, not thrown`() {
        runBlocking {
            val fx = CompanyServiceFixture()

            val result = fx.service.deleteCompany(adminUserId = 1, companyId = 404)

            result.fold(onSuccess = { fail("expected NOT_A_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.NOT_A_MEMBER, it)
            })
        }
    }

    // --- listMembers() ---

    @Test
    fun `any member, admin or not, can list the company's members`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val members =
                fx.service
                    .listMembers(
                        userId = 2,
                        companyId = created.id,
                    ).fold(onSuccess = { it }, onError = { fail("$it") })

            assertEquals(setOf(1, 2), members.map { it.userId }.toSet())
        }
    }

    @Test
    fun `a non-member cannot list members`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.listMembers(userId = 99, companyId = created.id)

            result.fold(onSuccess = { fail("expected NOT_A_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.NOT_A_MEMBER, it)
            })
        }
    }

    // --- promoteMember() / demoteMember() ---

    @Test
    fun `an admin can promote a plain member to admin`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.promoteMember(adminUserId = 1, companyId = created.id, targetUserId = 2)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(CompanyRole.ADMIN, fx.membershipRepository.find(created.id, 2)?.role)
        }
    }

    @Test
    fun `a plain member cannot promote anyone`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.joinCompany(3, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.promoteMember(adminUserId = 2, companyId = created.id, targetUserId = 3)

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(CompanyError.NOT_ADMIN, it)
            })
        }
    }

    @Test
    fun `promoting someone who isn't a member of the company is rejected`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.promoteMember(adminUserId = 1, companyId = created.id, targetUserId = 99)

            result.fold(onSuccess = { fail("expected TARGET_NOT_A_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.TARGET_NOT_A_MEMBER, it)
            })
        }
    }

    @Test
    fun `promoting an already-admin member is a harmless no-op`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.promoteMember(adminUserId = 1, companyId = created.id, targetUserId = 1)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `an admin can demote another admin down to a plain member`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.promoteMember(1, created.id, 2).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.demoteMember(adminUserId = 1, companyId = created.id, targetUserId = 2)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(CompanyRole.MEMBER, fx.membershipRepository.find(created.id, 2)?.role)
        }
    }

    @Test
    fun `demoting the only admin is rejected — a company must always have at least one`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.demoteMember(adminUserId = 1, companyId = created.id, targetUserId = 1)

            result.fold(onSuccess = { fail("expected LAST_ADMIN but got success") }, onError = {
                assertEquals(CompanyError.LAST_ADMIN, it)
            })
            assertEquals(CompanyRole.ADMIN, fx.membershipRepository.find(created.id, 1)?.role)
        }
    }

    // --- regenerateInviteCode() ---

    @Test
    fun `an admin can regenerate the company's invite code`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.regenerateInviteCode(adminUserId = 1, companyId = created.id)

            val updated = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(created.id, updated.id)
            assertNotEquals(created.inviteCode, updated.inviteCode)
        }
    }

    @Test
    fun `the old invite code stops working immediately after regeneration`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.regenerateInviteCode(adminUserId = 1, companyId = created.id).fold(
                onSuccess = {},
                onError = { fail("$it") },
            )

            val joinResult = fx.service.joinCompany(userId = 2, inviteCode = created.inviteCode)

            joinResult.fold(onSuccess = { fail("expected INVALID_INVITE_CODE but got success") }, onError = {
                assertEquals(CompanyError.INVALID_INVITE_CODE, it)
            })
        }
    }

    @Test
    fun `the new invite code works for joining`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            val regenerated =
                fx.service
                    .regenerateInviteCode(adminUserId = 1, companyId = created.id)
                    .fold(onSuccess = { it }, onError = { fail("$it") })

            val joinResult = fx.service.joinCompany(userId = 2, inviteCode = regenerated.inviteCode)

            joinResult.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(CompanyRole.MEMBER, fx.membershipRepository.find(created.id, 2)?.role)
        }
    }

    @Test
    fun `regenerating twice invalidates the first regenerated code too, not just the original`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            val firstRegen =
                fx.service
                    .regenerateInviteCode(adminUserId = 1, companyId = created.id)
                    .fold(onSuccess = { it }, onError = { fail("$it") })

            val secondRegen =
                fx.service
                    .regenerateInviteCode(adminUserId = 1, companyId = created.id)
                    .fold(onSuccess = { it }, onError = { fail("$it") })

            assertNotEquals(firstRegen.inviteCode, secondRegen.inviteCode)
            fx.service.joinCompany(userId = 2, inviteCode = firstRegen.inviteCode).fold(
                onSuccess = { fail("expected the first regenerated code to also be dead, but it still worked") },
                onError = { assertEquals(CompanyError.INVALID_INVITE_CODE, it) },
            )
            fx.service.joinCompany(userId = 3, inviteCode = secondRegen.inviteCode).fold(
                onSuccess = {},
                onError = { fail("expected the second (current) code to work, got $it") },
            )
        }
    }

    @Test
    fun `a plain member cannot regenerate the invite code`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.regenerateInviteCode(adminUserId = 2, companyId = created.id)

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(CompanyError.NOT_ADMIN, it)
            })
        }
    }

    @Test
    fun `regenerating the invite code for an unknown company is rejected cleanly, not thrown`() {
        runBlocking {
            val fx = CompanyServiceFixture()

            val result = fx.service.regenerateInviteCode(adminUserId = 1, companyId = 404)

            result.fold(onSuccess = { fail("expected NOT_A_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.NOT_A_MEMBER, it)
            })
        }
    }

    @Test
    fun `demoting one of two admins succeeds, since one admin remains`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.promoteMember(1, created.id, 2).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.demoteMember(adminUserId = 2, companyId = created.id, targetUserId = 1)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(CompanyRole.ADMIN, fx.membershipRepository.find(created.id, 2)?.role)
        }
    }
}
