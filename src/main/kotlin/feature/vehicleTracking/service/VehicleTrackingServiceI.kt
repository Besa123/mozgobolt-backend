/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.GpsPlausibility
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

class VehicleTrackingServiceI(
    private val vehicleAssignmentService: VehicleAssignmentService,
    private val vehicleRepository: VehicleRepository,
    private val cellIndexer: CellIndexer,
    private val hub: VehicleLocationHub,
    private val buffer: VehicleLocationBuffer,
    private val proximityAlertService: ProximityAlertService,
    private val tx: TransactionalRunner,
) : VehicleTrackingService {
    override suspend fun ingestTelemetry(
        vendorUserId: Int,
        points: List<TelemetryPoint>,
    ): AppResult<Unit, TelemetryError> {
        val activeAssignment =
            vehicleAssignmentService.findActiveForVendor(vendorUserId)
                ?: return AppResult.Error(TelemetryError.NOT_LINKED_TO_VEHICLE)

        // Resolved once per batch, not per point — every point in this batch belongs to the same
        // active assignment's vehicle, so its company can't change mid-batch.
        val companyId =
            tx.transactional {
                vehicleRepository.findById(activeAssignment.vehicleId)?.companyId
            }

        val acceptedUpdates = mutableListOf<VehicleLocationUpdate>()
        val acceptedPoints = mutableListOf<GeoPoint>()
        var previous = hub.lastKnownLocation(activeAssignment.vehicleId)

        points.forEach { point ->
            if (point.recordedAt.isBefore(activeAssignment.startedAt)) {
                logger.warn {
                    "Dropping telemetry recorded before assignment start for vehicle ${activeAssignment.vehicleId}"
                }
                return@forEach
            }

            val isPlausible =
                GpsPlausibility.isPlausibleMovement(
                    previous = previous,
                    candidateLatitude = point.latitude,
                    candidateLongitude = point.longitude,
                    candidateRecordedAt = point.recordedAt,
                )

            if (!isPlausible) {
                logger.warn { "Dropping an implausible GPS jump for vehicle ${activeAssignment.vehicleId}" }
                return@forEach
            }

            val update =
                VehicleLocationUpdate(
                    vehicleId = activeAssignment.vehicleId,
                    vendorUserId = vendorUserId,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    recordedAt = point.recordedAt,
                    cellId = cellIndexer.cellFor(point.latitude, point.longitude),
                    assignmentId = activeAssignment.id,
                    sharesLocationWithCustomers = activeAssignment.sharesLocationWithCustomers,
                )
            acceptedUpdates += update
            acceptedPoints += GeoPoint(point.latitude, point.longitude)
            previous = update
        }

        if (acceptedUpdates.isNotEmpty() &&
            !vehicleAssignmentService.recordTelemetryReceived(
                assignmentId = activeAssignment.id,
                vendorUserId = vendorUserId,
                receivedAt = Instant.now(),
            )
        ) {
            return AppResult.Error(TelemetryError.NOT_LINKED_TO_VEHICLE)
        }

        var droppedHistoryPoints = 0
        val publishedPoints = mutableListOf<GeoPoint>()
        acceptedUpdates.forEachIndexed { index, update ->
            if (hub.publishIfCurrent(update)) {
                if (!buffer.enqueue(update)) droppedHistoryPoints++
                publishedPoints += acceptedPoints[index]
            }
        }

        if (droppedHistoryPoints > 0) {
            logger.warn {
                "Dropped $droppedHistoryPoints telemetry history point(s) because the in-memory buffer is full"
            }
        }

        val shouldEvaluateProximityAlerts =
            activeAssignment.sharesLocationWithCustomers && publishedPoints.isNotEmpty()
        if (companyId != null && shouldEvaluateProximityAlerts) {
            proximityAlertService.evaluateBatch(activeAssignment.vehicleId, companyId, publishedPoints)
        }

        return AppResult.Success(Unit)
    }
}
