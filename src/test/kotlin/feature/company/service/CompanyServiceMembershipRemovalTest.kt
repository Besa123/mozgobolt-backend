package com.mozgobolt.feature.company.service

import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class CompanyServiceMembershipRemovalTest {
    // --- removeMember() ---

    @Test
    fun `an admin can remove a plain member`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.removeMember(adminUserId = 1, companyId = created.id, targetUserId = 2)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertNull(fx.membershipRepository.find(created.id, 2))
        }
    }

    @Test
    fun `a plain member cannot remove anyone`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.joinCompany(3, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.removeMember(adminUserId = 2, companyId = created.id, targetUserId = 3)

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(CompanyError.NOT_ADMIN, it)
            })
        }
    }

    @Test
    fun `removing the only admin is rejected`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            // Only one admin (id 1) — an admin trying to remove THEMSELF via removeMember (as
            // opposed to leaveCompany) must still be blocked by the same guard.
            val result = fx.service.removeMember(adminUserId = 1, companyId = created.id, targetUserId = 1)

            result.fold(onSuccess = { fail("expected LAST_ADMIN but got success") }, onError = {
                assertEquals(CompanyError.LAST_ADMIN, it)
            })
        }
    }

    @Test
    fun `removing a member who currently has an active session on this company's vehicle ends that session`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            val vehicle = fx.vehicleRepository.seed(created.id)
            val activeAssignment = fx.assignmentRepository.seedActive(vehicle.id, vendorUserId = 2)

            fx.service.removeMember(adminUserId = 1, companyId = created.id, targetUserId = 2).fold(
                onSuccess = {},
                onError = { fail("$it") },
            )

            assertNull(fx.assignmentRepository.findActiveForVendor(2))
            assertTrue(
                fx.syncService.recorded.any {
                    it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT && it.entityId == activeAssignment.id
                },
            )
            // The actual regression this test guards: removeMember() used to end the assignment by
            // reaching directly into the repository, silently skipping the "clear it from the live
            // map" step that VehicleAssignmentService.unlink()/endActiveAssignment() always do —
            // leaving the vehicle stuck on the live map forever. It now goes through
            // VehicleAssignmentService.endActiveAssignmentForVendorInCompany(), which doesn't skip it.
            assertEquals(listOf(vehicle.id), fx.locationHub.markedOffline)
        }
    }

    @Test
    fun `removing a member whose active session is on a DIFFERENT company's vehicle leaves it untouched`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val companyA = fx.service.createCompany(1, "A").fold(onSuccess = { it }, onError = { fail("$it") })
            val companyB = fx.service.createCompany(3, "B").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, companyA.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.joinCompany(2, companyB.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            val vehicleInB = fx.vehicleRepository.seed(companyB.id)
            fx.assignmentRepository.seedActive(vehicleInB.id, vendorUserId = 2)

            // Removing user 2 from company A must NOT touch their active session on company B's
            // vehicle.
            fx.service.removeMember(adminUserId = 1, companyId = companyA.id, targetUserId = 2).fold(
                onSuccess = {},
                onError = { fail("$it") },
            )

            assertEquals(vehicleInB.id, fx.assignmentRepository.findActiveForVendor(2)?.vehicleId)
            assertTrue(fx.locationHub.markedOffline.isEmpty())
        }
    }

    @Test
    fun `removing someone who isn't a member of the company is rejected`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.removeMember(adminUserId = 1, companyId = created.id, targetUserId = 99)

            result.fold(onSuccess = { fail("expected TARGET_NOT_A_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.TARGET_NOT_A_MEMBER, it)
            })
        }
    }

    // --- leaveCompany() ---

    @Test
    fun `a plain member can leave the company voluntarily`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.leaveCompany(userId = 2, companyId = created.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertNull(fx.membershipRepository.find(created.id, 2))
        }
    }

    @Test
    fun `the only admin cannot leave the company alone`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.leaveCompany(userId = 1, companyId = created.id)

            result.fold(onSuccess = { fail("expected LAST_ADMIN but got success") }, onError = {
                assertEquals(CompanyError.LAST_ADMIN, it)
            })
        }
    }

    @Test
    fun `one of two admins can leave, since one admin remains`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.promoteMember(1, created.id, 2).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.leaveCompany(userId = 1, companyId = created.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `leaving a company the user isn't a member of is rejected`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.leaveCompany(userId = 99, companyId = created.id)

            result.fold(onSuccess = { fail("expected TARGET_NOT_A_MEMBER but got success") }, onError = {
                assertEquals(CompanyError.TARGET_NOT_A_MEMBER, it)
            })
        }
    }

    @Test
    fun `leaving ends the leaver's own active session on that company's vehicle`() {
        runBlocking {
            val fx = CompanyServiceFixture()
            val created = fx.service.createCompany(1, "FamilyFrost").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service.joinCompany(2, created.inviteCode).fold(onSuccess = {}, onError = { fail("$it") })
            val vehicle = fx.vehicleRepository.seed(created.id)
            fx.assignmentRepository.seedActive(vehicle.id, vendorUserId = 2)

            fx.service.leaveCompany(userId = 2, companyId = created.id).fold(onSuccess = {}, onError = { fail("$it") })

            assertNull(fx.assignmentRepository.findActiveForVendor(2))
            assertEquals(listOf(vehicle.id), fx.locationHub.markedOffline)
        }
    }
}
