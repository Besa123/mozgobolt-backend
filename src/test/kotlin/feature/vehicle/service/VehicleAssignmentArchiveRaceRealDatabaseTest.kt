/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

class VehicleAssignmentArchiveRaceRealDatabaseTest {
    private data class Setup(
        val oldCompanyId: Int,
        val oldVehicleId: Int,
        val newVehicleId: Int,
    )

    private class BlockingAssignmentEnd(
        private val delegate: VehicleAssignmentService,
        private val endRequested: CompletableDeferred<Unit>,
        private val continueEnd: CompletableDeferred<Unit>,
    ) : VehicleAssignmentService by delegate {
        override suspend fun endActiveAssignmentForVendorInCompany(
            vendorUserId: Int,
            companyId: Int,
        ): VehicleAssignment? {
            endRequested.complete(Unit)
            continueEnd.await()
            return delegate.endActiveAssignmentForVendorInCompany(vendorUserId, companyId)
        }
    }

    @Test
    fun `archive succeeds when a concurrent link already moved the vendor to another vehicle`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val syncService = FakeSyncService()
            val setup =
                tx.transactional {
                    val oldCompany =
                        companyRepository.create(
                            "Archive race source",
                            SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val newCompany =
                        companyRepository.create(
                            "Archive race destination",
                            SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    membershipRepository.addIfAbsent(oldCompany.id, 1, CompanyRole.ADMIN)
                    membershipRepository.addIfAbsent(newCompany.id, 2, CompanyRole.ADMIN)
                    membershipRepository.addIfAbsent(newCompany.id, 1, CompanyRole.MEMBER)
                    val oldVehicle =
                        checkNotNull(vehicleRepository.create(oldCompany.id, "Old truck", "ARCH-RACE-OLD"))
                    val newVehicle =
                        checkNotNull(vehicleRepository.create(newCompany.id, "New truck", "ARCH-RACE-NEW"))
                    Setup(
                        oldCompanyId = oldCompany.id,
                        oldVehicleId = oldVehicle.id,
                        newVehicleId = newVehicle.id,
                    )
                }

            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    companyRepository = companyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = membershipRepository,
                    syncService = syncService,
                    hub = NoOpVehicleLocationHub(),
                    tx = tx,
                )
            assignmentService
                .link(vendorUserId = 1, vehicleId = setup.oldVehicleId)
                .fold(onSuccess = {}, onError = { fail("setup assignment failed: $it") })

            val endRequested = CompletableDeferred<Unit>()
            val continueEnd = CompletableDeferred<Unit>()
            val blockingAssignmentService =
                BlockingAssignmentEnd(assignmentService, endRequested, continueEnd)
            val vehicleService =
                VehicleServiceI(
                    companyRepository = companyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = membershipRepository,
                    vehicleAssignmentService = blockingAssignmentService,
                    syncService = syncService,
                    imageSanitizer = FakeImageSanitizer(),
                    virusScanner = FakeVirusScanner(),
                    imageStorage = FakeImageStorage(),
                    tx = tx,
                )

            coroutineScope {
                val archive =
                    async(Dispatchers.IO) {
                        vehicleService.archiveVehicle(1, setup.oldCompanyId, setup.oldVehicleId)
                    }

                try {
                    withTimeout(5_000) { endRequested.await() }
                    val link =
                        async(Dispatchers.IO) {
                            assignmentService.link(vendorUserId = 1, vehicleId = setup.newVehicleId)
                        }
                    link.await().fold(
                        onSuccess = {},
                        onError = { fail("link to the new vehicle failed: $it") },
                    )
                } finally {
                    continueEnd.complete(Unit)
                }

                archive.await().fold(
                    onSuccess = {},
                    onError = { fail("archive should tolerate the assignment moving away: $it") },
                )
            }

            assertNotNull(tx.transactional { vehicleRepository.findById(setup.oldVehicleId)?.archivedAt })
            assertNull(tx.transactional { assignmentRepository.findActiveForVehicle(setup.oldVehicleId) })
            assertEquals(
                setup.newVehicleId,
                tx.transactional { assignmentRepository.findActiveForVendor(1)?.vehicleId },
            )
        }
    }
}
