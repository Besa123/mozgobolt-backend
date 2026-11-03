/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

private const val STALE_ASSIGNMENT_BATCH_SIZE = 100
internal val STALE_ASSIGNMENT_INACTIVITY_WINDOW = 12.hours
private val DEFAULT_CHECK_INTERVAL = 15.minutes

class VehicleAssignmentStaleSessionReaper(
    private val vehicleAssignmentRepository: VehicleAssignmentRepository,
    private val syncService: SyncService,
    private val hub: VehicleLocationHub,
    private val tx: TransactionalRunner,
    private val backgroundScope: ApplicationBackgroundScope,
    private val checkInterval: Duration = DEFAULT_CHECK_INTERVAL,
    private val inactivityWindow: Duration = STALE_ASSIGNMENT_INACTIVITY_WINDOW,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("vehicle-assignment-stale-session-reaper")) {
                while (isActive) {
                    delay(checkInterval)
                    runSuspendCatching { expireStaleAssignments() }
                        .onFailure {
                            logger.error(it) { "Failed to expire stale vehicle assignments; retrying next interval" }
                        }
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun expireStaleAssignments(): Int {
        val endedAssignments =
            tx.transactional {
                val staleAssignments =
                    vehicleAssignmentRepository.findStaleActiveBefore(
                        cutoff = Instant.now().minusMillis(inactivityWindow.inWholeMilliseconds),
                        limit = STALE_ASSIGNMENT_BATCH_SIZE,
                    )
                val endedAt = Instant.now()
                staleAssignments.mapNotNull { assignment ->
                    if (!vehicleAssignmentRepository.endAssignment(assignment.id, endedAt)) {
                        null
                    } else {
                        syncService.recordChange(
                            userId = assignment.vendorUserId,
                            entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                            entityId = assignment.id,
                            operation = SyncOperation.UPSERT,
                        )
                        assignment.copy(endedAt = endedAt)
                    }
                }
            }
        endedAssignments.forEach { assignment -> hub.markOffline(assignment.vehicleId, assignment.id) }
        return endedAssignments.size
    }
}
