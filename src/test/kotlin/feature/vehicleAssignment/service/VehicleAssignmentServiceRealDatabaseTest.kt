/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleAssignment.withRealVehicleAssignmentDatabase
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The unit tests in [VehicleAssignmentServiceITest] prove the *service* correctly reacts to
 * `startAssignment` returning null; this proves the underlying assumption those tests are built
 * on — that the real partial unique indexes on `vehicle_assignments` actually fire under a
 * genuinely concurrent Postgres workload, and that the resulting `ExposedSQLException` is caught
 * with the right SQL state — rather than trusting that by reasoning alone. This is exactly the
 * class of bug CLAUDE.md calls out: invisible to unit-test fakes, only caught by a real DB.
 */
class VehicleAssignmentServiceRealDatabaseTest {
    private class RecordingVehicleLocationHub : VehicleLocationHub by NoOpVehicleLocationHub() {
        val markedOffline = mutableListOf<Pair<Int, Int?>>()

        override fun markOffline(vehicleId: Int) {
            markedOffline += vehicleId to null
        }

        override fun markOffline(
            vehicleId: Int,
            assignmentId: Int,
        ): Boolean {
            markedOffline += vehicleId to assignmentId
            return true
        }
    }

    private class FailingSyncService : SyncService by FakeSyncService() {
        override suspend fun recordChange(
            userId: Int,
            entityType: SyncEntityType,
            entityId: Int,
            operation: SyncOperation,
            originDeviceId: String?,
        ) {
            error("simulated sync write failure")
        }
    }

    @Test
    fun `current assignment read returns active vehicle details and null after the shift ends`() {
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleId = harness.seedSharedCompanyWithOneVehicle()
            val companyId =
                harness.tx.transactional {
                    requireNotNull(harness.vehicleRepository.findById(vehicleId)).companyId
                }

            assertNull(harness.service.findActiveDetailsForVendor(1))

            val created =
                harness.service.link(vendorUserId = 1, vehicleId = vehicleId).fold(
                    onSuccess = { it },
                    onError = { error("setup failed: $it") },
                )
            assertEquals(
                ActiveVehicleAssignment(
                    id = created.id,
                    vehicleId = vehicleId,
                    companyId = companyId,
                    vehicleLabel = "Shared Truck",
                    licensePlate = "SHARED-1",
                ),
                harness.service.findActiveDetailsForVendor(1),
            )
            assertNull(harness.service.findActiveDetailsForVendor(2), "another vendor must not see this assignment")

            harness.service.unlink(vendorUserId = 1, vehicleId = vehicleId)
            assertNull(harness.service.findActiveDetailsForVendor(1))
        }
    }

    @Test
    fun `link persists sharing consent and stale assignment cleanup respects the last accepted telemetry`() {
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val (staleVehicleId, recentVehicleId) = harness.seedSharedCompanyWithTwoVehicles()
            val now = Instant.now()
            val staleAssignment =
                harness.tx.transactional {
                    harness.assignmentRepository.startAssignment(
                        staleVehicleId,
                        vendorUserId = 1,
                        startedAt = now.minusSeconds(13 * 60 * 60),
                        locationSharingConsentedAt = now.minusSeconds(13 * 60 * 60),
                    )
                } ?: fail("failed to create stale assignment")
            val recentAssignment =
                harness.tx.transactional {
                    harness.assignmentRepository.startAssignment(
                        recentVehicleId,
                        vendorUserId = 2,
                        startedAt = now.minusSeconds(20 * 60 * 60),
                        locationSharingConsentedAt = now.minusSeconds(20 * 60 * 60),
                    )
                } ?: fail("failed to create recent assignment")
            harness.tx.transactional {
                assertTrue(
                    harness.assignmentRepository.recordTelemetryReceived(
                        recentAssignment.id,
                        vendorUserId = 2,
                        receivedAt = now.minusSeconds(11 * 60 * 60),
                    ),
                )
            }

            val reaper =
                VehicleAssignmentStaleSessionReaper(
                    vehicleAssignmentRepository = harness.assignmentRepository,
                    syncService = FakeSyncService(),
                    hub = NoOpVehicleLocationHub(),
                    tx = harness.tx,
                    backgroundScope = ApplicationBackgroundScope(),
                )

            val ended = reaper.expireStaleAssignments()

            assertEquals(1, ended)
            assertNull(harness.tx.transactional { harness.assignmentRepository.findActiveForVendor(1) })
            assertEquals(
                recentAssignment.id,
                harness.tx.transactional { harness.assignmentRepository.findActiveForVendor(2)?.id },
            )
            assertTrue(staleAssignment.sharesLocationWithCustomers)
        }
    }

    @Test
    fun `sync failure rolls back assignment end without clearing the live vehicle`() {
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleId = harness.seedSharedCompanyWithOneVehicle()
            harness.service
                .link(vendorUserId = 1, vehicleId = vehicleId)
                .fold(onSuccess = {}, onError = { fail("setup assignment failed: $it") })
            val hub = RecordingVehicleLocationHub()
            val service =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = harness.assignmentRepository,
                    companyRepository = harness.companyRepository,
                    vehicleRepository = harness.vehicleRepository,
                    membershipRepository = harness.membershipRepository,
                    syncService = FailingSyncService(),
                    hub = hub,
                    tx = harness.tx,
                )

            assertFailsWith<IllegalStateException> {
                service.unlink(vendorUserId = 1, vehicleId = vehicleId)
            }

            assertNotNull(harness.tx.transactional { harness.assignmentRepository.findActiveForVendor(1) })
            assertTrue(hub.markedOffline.isEmpty(), "a rolled-back end must not emit a live offline signal")
        }
    }

    @Test
    fun `ending an assignment twice does not overwrite its original ended timestamp`() {
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleId = harness.seedSharedCompanyWithOneVehicle()
            val assignment =
                harness.service
                    .link(vendorUserId = 1, vehicleId = vehicleId)
                    .fold(onSuccess = { it }, onError = { fail("setup assignment failed: $it") })
            val firstEnd = Instant.now().plusSeconds(10)

            assertTrue(harness.tx.transactional { harness.assignmentRepository.endAssignment(assignment.id, firstEnd) })
            assertFalse(
                harness.tx.transactional {
                    harness.assignmentRepository.endAssignment(assignment.id, firstEnd.plusSeconds(10))
                },
            )
            val persistedEnd =
                harness.tx.transactional {
                    VehicleAssignmentsTable
                        .selectAll()
                        .where { VehicleAssignmentsTable.id eq assignment.id }
                        .single()[VehicleAssignmentsTable.endedAt]
                }

            assertEquals(firstEnd, persistedEnd)
        }
    }

    @Test
    fun `two concurrent link requests for the same vehicle by different vendors only ever produce one winner`() {
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleId = harness.seedSharedCompanyWithOneVehicle()

            val results =
                coroutineScope {
                    val first = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleId) }
                    val second = async { harness.service.link(vendorUserId = 2, vehicleId = vehicleId) }
                    listOf(first.await(), second.await())
                }

            val successes = results.count { it is AppResult.Success }
            val rejections =
                results.count {
                    it is AppResult.Error && it.errorType == VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED
                }
            assertEquals(1, successes, "exactly one concurrent link should win: $results")
            assertEquals(1, rejections, "the loser must see a clean VEHICLE_ALREADY_ASSIGNED, not crash: $results")

            val activeAssignment =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(
                        vehicleId,
                    )
                }
            assertNotNull(activeAssignment, "the winner's assignment must actually be the active one on record")
        }
    }

    @Test
    fun `linking then immediately relinking to the same vehicle concurrently never creates two active rows`() {
        // The vendor calling link() twice for the SAME vehicle at once (e.g. a flaky client
        // double-submitting) should be idempotent, not a race — this exercises the
        // "activeOnVehicle belongs to me already" branch under real concurrency too.
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleId = harness.seedSharedCompanyWithOneVehicle()

            val results =
                coroutineScope {
                    val first = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleId) }
                    val second = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleId) }
                    listOf(first.await(), second.await())
                }

            assertEquals(
                2,
                results.count { it is AppResult.Success },
                "relinking to your own vehicle must never fail: $results",
            )

            val allAssignmentsForVehicle =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(vehicleId)
                }
            assertNotNull(allAssignmentsForVehicle)
            assertEquals(1, allAssignmentsForVehicle.vendorUserId)
        }
    }

    @Test
    fun `one vendor cannot win concurrent links across different companies`() {
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleIds =
                harness.tx.transactional {
                    val companyOne =
                        harness.companyRepository.create(
                            name = "Vendor Race Company One",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val companyTwo =
                        harness.companyRepository.create(
                            name = "Vendor Race Company Two",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    harness.membershipRepository.addIfAbsent(companyOne.id, 1, CompanyRole.MEMBER)
                    harness.membershipRepository.addIfAbsent(companyTwo.id, 1, CompanyRole.MEMBER)
                    val vehicleOne =
                        harness.vehicleRepository.create(companyOne.id, "One", "VENDOR-RACE-ONE")!!.id
                    val vehicleTwo =
                        harness.vehicleRepository.create(companyTwo.id, "Two", "VENDOR-RACE-TWO")!!.id
                    vehicleOne to vehicleTwo
                }
            val syncService = FakeSyncService()
            val repository =
                CoordinatedVendorAssignmentRepository(
                    delegate = harness.assignmentRepository,
                    vendorUserId = 1,
                    barrier = CyclicBarrier(2),
                )
            val service =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = repository,
                    companyRepository = harness.companyRepository,
                    vehicleRepository = harness.vehicleRepository,
                    membershipRepository = harness.membershipRepository,
                    syncService = syncService,
                    hub =
                        com.mozgobolt.feature.vehicleTracking
                            .NoOpVehicleLocationHub(),
                    tx = harness.tx,
                )

            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { service.link(vendorUserId = 1, vehicleId = vehicleIds.first) },
                        async(Dispatchers.IO) { service.link(vendorUserId = 1, vehicleId = vehicleIds.second) },
                    ).map { it.await() }
                }

            assertEquals(1, results.count { it is AppResult.Success })
            assertEquals(
                1,
                results.count {
                    it is AppResult.Error && it.errorType == VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED
                },
            )
            val active = harness.tx.transactional { harness.assignmentRepository.findActiveForVendor(1) }
            assertNotNull(active)
            assertTrue(active.vehicleId in vehicleIds.toList())
            assertEquals(
                1,
                syncService.recorded.count { it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT },
                "the losing transaction must roll back without publishing a sync event",
            )
        }
    }

    @Test
    fun `REGRESSION losing a concurrent race for a different vehicle never loses your own current session`() {
        // This is the scenario a fake in-memory repository can never prove: vendor 1 is already
        // actively driving vehicle A when they attempt to switch to vehicle B, racing vendor 2 for
        // it. If vendor 1 loses that race, link()'s own endAssignment(vehicle A) — issued before
        // the losing startAssignment(vehicle B) is even attempted — must be rolled back by the real
        // transaction, not committed. Only a real Postgres transaction can actually demonstrate
        // that rollback; a fake repository has no transactional semantics to roll back in the first
        // place, so this exact regression is invisible to unit tests, per CLAUDE.md.
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val (vehicleA, vehicleB) = harness.seedSharedCompanyWithTwoVehicles()

            harness.service
                .link(vendorUserId = 1, vehicleId = vehicleA)
                .fold(onSuccess = { it }, onError = { fail("setup failed: $it") })

            val results =
                coroutineScope {
                    val vendorOneAttempt = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleB) }
                    val vendorTwoAttempt = async { harness.service.link(vendorUserId = 2, vehicleId = vehicleB) }
                    listOf(vendorOneAttempt.await(), vendorTwoAttempt.await())
                }

            val vendorOneWonVehicleB = results[0] is AppResult.Success

            val activeOnVehicleA =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(
                        vehicleA,
                    )
                }
            if (vendorOneWonVehicleB) {
                // The ordinary case: vendor 1 genuinely switched vehicles, so their old session on
                // A is correctly gone, not a bug.
                assertNull(activeOnVehicleA, "vendor 1 switched to B, so A must now be free")
            } else {
                // The regression this test exists for: vendor 1 lost the race for B, so their
                // EXISTING session on A must have survived — not silently erased by a transaction
                // that itself reports failure.
                assertNotNull(activeOnVehicleA, "vendor 1 lost the race for B — their session on A must survive")
                assertEquals(1, activeOnVehicleA.vendorUserId)
            }

            val activeOnVehicleB =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(
                        vehicleB,
                    )
                }
            assertNotNull(activeOnVehicleB, "exactly one of vendor 1 or vendor 2 must hold vehicle B")
        }
    }

    private class CoordinatedVendorAssignmentRepository(
        private val delegate: VehicleAssignmentRepository,
        private val vendorUserId: Int,
        private val barrier: CyclicBarrier,
    ) : VehicleAssignmentRepository by delegate {
        private val vendorLookupCount = AtomicInteger()

        override suspend fun findActiveForVendor(vendorUserId: Int) =
            delegate.findActiveForVendor(vendorUserId).also {
                if (vendorUserId == this.vendorUserId && vendorLookupCount.incrementAndGet() <= 2) {
                    barrier.await(10, TimeUnit.SECONDS)
                }
            }
    }
}
