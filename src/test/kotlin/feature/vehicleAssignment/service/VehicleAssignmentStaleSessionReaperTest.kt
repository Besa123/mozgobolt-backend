/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class VehicleAssignmentStaleSessionReaperTest {
    @Test
    fun `successful stale assignment cleanup records sync and clears its live location`() {
        runBlocking {
            val assignment =
                VehicleAssignment(
                    id = 4,
                    vehicleId = 12,
                    vendorUserId = 8,
                    startedAt = Instant.EPOCH,
                )
            val repository = FakeVehicleAssignmentRepository(listOf(assignment))
            val syncService = FakeSyncService()
            val hub = RecordingVehicleLocationHub()
            val backgroundScope = ApplicationBackgroundScope()
            val reaper =
                VehicleAssignmentStaleSessionReaper(
                    vehicleAssignmentRepository = repository,
                    syncService = syncService,
                    hub = hub,
                    tx = InlineTransactionalRunner,
                    backgroundScope = backgroundScope,
                    inactivityWindow = Duration.ZERO,
                )

            try {
                val endedCount = reaper.expireStaleAssignments()

                assertEquals(1, endedCount)
                assertEquals(listOf(assignment.id), repository.endedAssignmentIds)
                assertEquals(100, repository.lastRequestedLimit)
                assertEquals(1, syncService.recorded.size)
                assertEquals(SyncEntityType.VEHICLE_ASSIGNMENT, syncService.recorded.single().entityType)
                assertEquals(SyncOperation.UPSERT, syncService.recorded.single().operation)
                assertEquals(assignment.id, syncService.recorded.single().entityId)
                assertEquals(listOf(assignment.vehicleId to assignment.id), hub.endedAssignments)
            } finally {
                backgroundScope.cancel()
            }
        }
    }

    @Test
    fun `a concurrent assignment end is not recorded or notified twice`() {
        runBlocking {
            val assignment =
                VehicleAssignment(
                    id = 4,
                    vehicleId = 12,
                    vendorUserId = 8,
                    startedAt = Instant.EPOCH,
                )
            val repository = FakeVehicleAssignmentRepository(listOf(assignment), endSucceeds = false)
            val syncService = FakeSyncService()
            val hub = RecordingVehicleLocationHub()
            val backgroundScope = ApplicationBackgroundScope()
            val reaper =
                VehicleAssignmentStaleSessionReaper(
                    vehicleAssignmentRepository = repository,
                    syncService = syncService,
                    hub = hub,
                    tx = InlineTransactionalRunner,
                    backgroundScope = backgroundScope,
                    inactivityWindow = Duration.ZERO,
                )

            try {
                val endedCount = reaper.expireStaleAssignments()

                assertEquals(0, endedCount)
                assertTrue(syncService.recorded.isEmpty())
                assertTrue(hub.endedAssignments.isEmpty())
            } finally {
                backgroundScope.cancel()
            }
        }
    }
}

private class FakeVehicleAssignmentRepository(
    private val staleAssignments: List<VehicleAssignment>,
    private val endSucceeds: Boolean = true,
) : VehicleAssignmentRepository {
    val endedAssignmentIds = mutableListOf<Int>()
    var lastRequestedLimit: Int? = null
        private set

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? = error("not exercised")

    override suspend fun findActiveDetailsForVendor(vendorUserId: Int): ActiveVehicleAssignment? =
        error("not exercised")

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? = error("not exercised")

    override suspend fun findAllForCompany(companyId: Int): List<VehicleAssignment> = error("not exercised")

    override suspend fun findStaleActiveBefore(
        cutoff: Instant,
        limit: Int,
    ): List<VehicleAssignment> {
        lastRequestedLimit = limit
        return staleAssignments.take(limit)
    }

    override suspend fun startAssignment(
        vehicleId: Int,
        vendorUserId: Int,
        startedAt: Instant,
    ): VehicleAssignment? = error("not exercised")

    override suspend fun endAssignment(
        id: Int,
        endedAt: Instant,
    ): Boolean {
        if (!endSucceeds) return false
        endedAssignmentIds += id
        return true
    }
}

private class RecordingVehicleLocationHub : VehicleLocationHub {
    val endedAssignments = mutableListOf<Pair<Int, Int>>()

    override fun publish(update: VehicleLocationUpdate) = error("not exercised")

    override fun markOffline(vehicleId: Int) = error("use the assignment-scoped overload")

    override fun markOffline(
        vehicleId: Int,
        assignmentId: Int,
    ): Boolean {
        endedAssignments += vehicleId to assignmentId
        return true
    }

    override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? = error("not exercised")

    override fun subscribeNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<VehicleLiveEvent> = emptyFlow()

    override fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate> = error("not exercised")
}

private object InlineTransactionalRunner : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = block()
}
