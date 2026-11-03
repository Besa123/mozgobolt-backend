/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.service

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.company.CompanyTestHarness
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.withRealCompanyDatabase
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.fail

class CompanyInviteRevocationRealDatabaseTest {
    @Test
    fun `join waiting on invite rotation cannot use the revoked code`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "Invite Rotation").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            val coordinated = coordinatedRepository()
            val service = service(harness, coordinated.repository)

            val results =
                coroutineScope {
                    val rotation =
                        async(Dispatchers.IO) {
                            service.regenerateInviteCode(adminUserId = 1, companyId = company.id)
                        }
                    withTimeout(5_000) { coordinated.firstLockAcquired.await() }

                    val join =
                        async(Dispatchers.IO) {
                            service.joinCompany(userId = 2, inviteCode = company.inviteCode)
                        }
                    try {
                        withTimeout(5_000) { coordinated.secondLockRequested.await() }
                        assertEquals(
                            null,
                            withTimeoutOrNull(250) { coordinated.secondLockAcquired.await() },
                            "the join must wait for invite rotation's company-row lock",
                        )
                    } finally {
                        coordinated.releaseFirstLock.complete(Unit)
                    }

                    rotation.await() to join.await()
                }

            results.first.fold(
                onSuccess = { assertNotEquals(company.inviteCode, it.inviteCode) },
                onError = { error("invite rotation failed: $it") },
            )
            results.second.fold(
                onSuccess = { fail("join with the revoked invite code succeeded") },
                onError = { assertEquals(CompanyError.INVALID_INVITE_CODE, it) },
            )
            assertNull(harness.tx.transactional { harness.membershipRepository.find(company.id, 2) })
        }
    }

    @Test
    fun `admin action waiting on membership demotion rechecks authorization under the company lock`() {
        skipIfNoDocker()

        withRealCompanyDatabase { harness ->
            val company =
                harness.service.createCompany(creatorUserId = 1, name = "Admin Revocation").fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            harness.service.joinCompany(userId = 2, inviteCode = company.inviteCode).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )
            harness.service.promoteMember(adminUserId = 1, companyId = company.id, targetUserId = 2).fold(
                onSuccess = {},
                onError = { error("setup failed: $it") },
            )
            val coordinated = coordinatedRepository()
            val service = service(harness, coordinated.repository)

            val outcomes =
                coroutineScope {
                    val demotion =
                        async(Dispatchers.IO) {
                            service.demoteMember(adminUserId = 1, companyId = company.id, targetUserId = 2)
                        }
                    withTimeout(5_000) { coordinated.firstLockAcquired.await() }

                    val inviteRotation =
                        async(Dispatchers.IO) {
                            service.regenerateInviteCode(adminUserId = 2, companyId = company.id)
                        }
                    try {
                        withTimeout(5_000) { coordinated.secondLockRequested.await() }
                        assertEquals(
                            null,
                            withTimeoutOrNull(250) { coordinated.secondLockAcquired.await() },
                            "the invite operation must wait for the in-flight membership demotion",
                        )
                    } finally {
                        coordinated.releaseFirstLock.complete(Unit)
                    }

                    demotion.await() to inviteRotation.await()
                }

            outcomes.first.fold(
                onSuccess = {},
                onError = { error("admin demotion failed: $it") },
            )
            outcomes.second.fold(
                onSuccess = { fail("demoted user retained admin permissions") },
                onError = { assertEquals(CompanyError.NOT_ADMIN, it) },
            )
            assertEquals(
                company.inviteCode,
                harness.tx.transactional { CompanyRepositoryI().findById(company.id)?.inviteCode },
            )
        }
    }

    private fun service(
        harness: CompanyTestHarness,
        companyRepository: CompanyRepository,
    ) = CompanyServiceI(
        companyRepository = companyRepository,
        membershipRepository = harness.membershipRepository,
        companyFavoriteRepository = CompanyFavoriteRepositoryI(),
        vehicleAssignmentService =
            VehicleAssignmentServiceI(
                vehicleAssignmentRepository = VehicleAssignmentRepositoryI(),
                companyRepository = CompanyRepositoryI(),
                vehicleRepository = VehicleRepositoryI(),
                membershipRepository = harness.membershipRepository,
                syncService = FakeSyncService(),
                hub = InMemoryVehicleLocationHub(H3CellIndexer()),
                tx = harness.tx,
            ),
        syncService = FakeSyncService(),
        tx = harness.tx,
    )

    private fun coordinatedRepository(): CoordinatedRepositoryFixture {
        val firstLockAcquired = CompletableDeferred<Unit>()
        val secondLockRequested = CompletableDeferred<Unit>()
        val secondLockAcquired = CompletableDeferred<Unit>()
        val releaseFirstLock = CompletableDeferred<Unit>()
        val repository =
            CoordinatedCompanyRepository(
                delegate = CompanyRepositoryI(),
                firstLockAcquired = firstLockAcquired,
                secondLockRequested = secondLockRequested,
                secondLockAcquired = secondLockAcquired,
                releaseFirstLock = releaseFirstLock,
            )
        return CoordinatedRepositoryFixture(
            repository,
            firstLockAcquired,
            secondLockRequested,
            secondLockAcquired,
            releaseFirstLock,
        )
    }

    private data class CoordinatedRepositoryFixture(
        val repository: CompanyRepository,
        val firstLockAcquired: CompletableDeferred<Unit>,
        val secondLockRequested: CompletableDeferred<Unit>,
        val secondLockAcquired: CompletableDeferred<Unit>,
        val releaseFirstLock: CompletableDeferred<Unit>,
    )

    private class CoordinatedCompanyRepository(
        private val delegate: CompanyRepository,
        private val firstLockAcquired: CompletableDeferred<Unit>,
        private val secondLockRequested: CompletableDeferred<Unit>,
        private val secondLockAcquired: CompletableDeferred<Unit>,
        private val releaseFirstLock: CompletableDeferred<Unit>,
    ) : CompanyRepository by delegate {
        private val lockCallCount = AtomicInteger()

        override suspend fun findByIdForUpdate(companyId: Int): Company? {
            val callNumber = lockCallCount.incrementAndGet()
            if (callNumber == 2) secondLockRequested.complete(Unit)

            val company = delegate.findByIdForUpdate(companyId)
            when (callNumber) {
                1 -> {
                    firstLockAcquired.complete(Unit)
                    releaseFirstLock.await()
                }

                2 -> {
                    secondLockAcquired.complete(Unit)
                }

                else -> {
                    error("Unexpected company lock call $callNumber")
                }
            }

            return company
        }
    }
}
