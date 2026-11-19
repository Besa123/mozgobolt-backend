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
import com.mozgobolt.feature.vehicleTracking.domain.NoOpVehicleTrackingMetrics
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingMetrics
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryDispatchPlan
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
    private val metrics: VehicleTrackingMetrics = NoOpVehicleTrackingMetrics,
) : VehicleTrackingService {
    override suspend fun prepareTelemetry(
        vendorUserId: Int,
        points: List<TelemetryPoint>,
    ): AppResult<TelemetryDispatchPlan, TelemetryError> {
        val assignmentAndCompany =
            tx.transactional {
                // Resolve once per batch; all points share this vehicle/company.
                // The assignment service's nested transaction joins this boundary.
                vehicleAssignmentService.findActiveForVendor(vendorUserId)?.let { assignment ->
                    assignment to vehicleRepository.findById(assignment.vehicleId)?.companyId
                }
            }
                ?: return AppResult.Error(TelemetryError.NOT_LINKED_TO_VEHICLE)
        val (activeAssignment, companyId) = assignmentAndCompany

        val acceptedUpdates =
            buildList {
                var previous = hub.lastKnownLocation(activeAssignment.vehicleId)

                points.forEach { point ->
                    if (point.recordedAt.isBefore(activeAssignment.startedAt)) {
                        logger.warn {
                            "Dropping telemetry recorded before assignment start " +
                                "for vehicle ${activeAssignment.vehicleId}"
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
                    add(update)
                    previous = update
                }
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

        return AppResult.Success(
            TelemetryDispatchPlan(
                vehicleId = activeAssignment.vehicleId,
                companyId = companyId,
                sharesLocationWithCustomers = activeAssignment.sharesLocationWithCustomers,
                updates = acceptedUpdates,
            ),
        )
    }

    override suspend fun publishTelemetry(plan: TelemetryDispatchPlan) {
        var droppedHistoryPoints = 0
        val publishedPoints = mutableListOf<GeoPoint>()
        val publishedUpdates = hub.publishBatchIfCurrent(plan.updates)
        for (update in publishedUpdates) {
            if (!buffer.enqueue(update)) droppedHistoryPoints++
            publishedPoints += GeoPoint(update.latitude, update.longitude)
        }

        if (droppedHistoryPoints > 0) {
            metrics.recordHistoryBufferOverflow(droppedHistoryPoints)
            logger.warn {
                "Dropped $droppedHistoryPoints telemetry history point(s) because the in-memory buffer is full"
            }
        }

        val shouldEvaluateProximityAlerts = plan.sharesLocationWithCustomers && publishedPoints.isNotEmpty()
        if (plan.companyId != null && shouldEvaluateProximityAlerts) {
            proximityAlertService.evaluateBatch(plan.vehicleId, plan.companyId, publishedPoints)
        }
    }
}
