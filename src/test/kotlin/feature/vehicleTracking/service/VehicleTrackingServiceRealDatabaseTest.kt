/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentStaleSessionReaper
import com.mozgobolt.feature.vehicleTracking.NoOpVehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleTrackingServiceRealDatabaseTest {
    private data class Evaluation(
        val vehicleId: Int,
        val companyId: Int,
        val points: List<GeoPoint>,
    )

    private class RecordingProximityAlertService : ProximityAlertService {
        var evaluation: Evaluation? = null

        override suspend fun evaluateBatch(
            vehicleId: Int,
            companyId: Int,
            points: List<GeoPoint>,
        ) {
            evaluation = Evaluation(vehicleId, companyId, points)
        }
    }

    @Test
    fun `telemetry resolves the vehicle company inside an Exposed transaction`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val vehicleId =
                tx.transactional {
                    val company =
                        companyRepository.create(
                            name = "Tracking transaction company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    membershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
                    checkNotNull(vehicleRepository.create(company.id, "Tracking van", "TRACK-123")).id
                }
            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    companyRepository = companyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = membershipRepository,
                    syncService = FakeSyncService(),
                    hub = NoOpVehicleLocationHub(),
                    tx = tx,
                )
            val assignment =
                assignmentService
                    .link(
                        vendorUserId = 1,
                        vehicleId = vehicleId,
                        shareLocationWithCustomers = true,
                    ).fold(
                        onSuccess = { it },
                        onError = { error("failed to create telemetry test assignment: $it") },
                    )
            val cellIndexer = H3CellIndexer()
            val buffer = InMemoryVehicleLocationBuffer()
            val proximityAlertService = RecordingProximityAlertService()
            val service =
                VehicleTrackingServiceI(
                    vehicleAssignmentService = assignmentService,
                    vehicleRepository = vehicleRepository,
                    cellIndexer = cellIndexer,
                    hub = NoOpVehicleLocationHub(),
                    buffer = buffer,
                    proximityAlertService = proximityAlertService,
                    tx = tx,
                )
            val recordedAt = Instant.now()

            val result =
                service.ingestTelemetry(
                    vendorUserId = 1,
                    points =
                        listOf(
                            TelemetryPoint(
                                latitude = 47.4979,
                                longitude = 19.0402,
                                recordedAt = recordedAt,
                            ),
                        ),
                )

            result.fold(
                onSuccess = { assertEquals(Unit, it) },
                onError = { fail("expected telemetry success but got $it") },
            )
            val persistedCandidates = buffer.drainAll()
            assertEquals(1, persistedCandidates.size)
            assertEquals(assignment.id, persistedCandidates.single().assignmentId)
            val evaluation = requireNotNull(proximityAlertService.evaluation)
            assertEquals(vehicleId, evaluation.vehicleId)
            assertEquals(GeoPoint(47.4979, 19.0402), evaluation.points.single())
            buffer.completeBatch(persistedCandidates)
        }
    }

    @Test
    fun `telemetry is not published when the stale reaper ends its assignment after lookup`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val assignmentStartedAt = Instant.now().minus(Duration.ofHours(13))
            val vehicleId =
                tx.transactional {
                    val company =
                        companyRepository.create(
                            name = "Stale telemetry company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    membershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
                    checkNotNull(vehicleRepository.create(company.id, "Stale telemetry van", "STALE-123")).id
                }
            val seededAssignment =
                tx.transactional {
                    assignmentRepository.startAssignment(vehicleId, 1, assignmentStartedAt)
                        ?: error("failed to create stale telemetry test assignment")
                }
            val hub = RecordingVehicleLocationHub()
            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    companyRepository = companyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = membershipRepository,
                    syncService = FakeSyncService(),
                    hub = hub,
                    tx = tx,
                )
            val reaper =
                VehicleAssignmentStaleSessionReaper(
                    vehicleAssignmentRepository = assignmentRepository,
                    syncService = FakeSyncService(),
                    hub = hub,
                    tx = tx,
                    backgroundScope = ApplicationBackgroundScope(),
                )
            val racingAssignmentService = ReapBeforeRecordingTelemetry(assignmentService, reaper)
            val buffer = InMemoryVehicleLocationBuffer()
            val proximityAlertService = RecordingProximityAlertService()
            val service =
                VehicleTrackingServiceI(
                    vehicleAssignmentService = racingAssignmentService,
                    vehicleRepository = vehicleRepository,
                    cellIndexer = H3CellIndexer(),
                    hub = hub,
                    buffer = buffer,
                    proximityAlertService = proximityAlertService,
                    tx = tx,
                )

            val result =
                service.ingestTelemetry(
                    vendorUserId = 1,
                    points =
                        listOf(
                            TelemetryPoint(
                                latitude = 47.4979,
                                longitude = 19.0402,
                                recordedAt = Instant.now(),
                            ),
                        ),
                )

            result.fold(
                onSuccess = { fail("expected stale assignment rejection but telemetry was accepted") },
                onError = { assertEquals(TelemetryError.NOT_LINKED_TO_VEHICLE, it) },
            )
            assertEquals(1, racingAssignmentService.endedAssignments)
            assertTrue(hub.published.isEmpty(), "an assignment ended before heartbeat acceptance must not publish")
            assertTrue(buffer.drainAll().isEmpty(), "rejected telemetry must not enter the persistence buffer")
            assertNull(proximityAlertService.evaluation, "rejected telemetry must not trigger buyer-facing alerts")
            assertNull(assignmentService.findActiveForVendor(1))
            assertEquals(seededAssignment.id, racingAssignmentService.endedAssignmentId)
        }
    }

    private class ReapBeforeRecordingTelemetry(
        private val delegate: VehicleAssignmentService,
        private val reaper: VehicleAssignmentStaleSessionReaper,
    ) : VehicleAssignmentService by delegate {
        var endedAssignments = 0
            private set
        var endedAssignmentId: Int? = null
            private set

        override suspend fun recordTelemetryReceived(
            assignmentId: Int,
            vendorUserId: Int,
            receivedAt: Instant,
        ): Boolean {
            endedAssignments = reaper.expireStaleAssignments()
            if (endedAssignments > 0) endedAssignmentId = assignmentId
            return delegate.recordTelemetryReceived(assignmentId, vendorUserId, receivedAt)
        }
    }

    private class RecordingVehicleLocationHub : VehicleLocationHub {
        val published = mutableListOf<VehicleLocationUpdate>()

        override fun publish(update: VehicleLocationUpdate) {
            published += update
        }

        override fun markOffline(vehicleId: Int) = Unit

        override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? =
            published.lastOrNull { it.vehicleId == vehicleId }

        override fun subscribeNear(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): Flow<VehicleLiveEvent> = emptyFlow()

        override fun snapshotNear(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): List<VehicleLocationUpdate> = emptyList()
    }
}
