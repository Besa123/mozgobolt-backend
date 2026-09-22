package com.mozgobolt.feature.vehicleTracking.service

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

private val logger = KotlinLogging.logger {}

class VehicleTrackingServiceI(
    private val vehicleAssignmentService: VehicleAssignmentService,
    private val vehicleRepository: VehicleRepository,
    private val cellIndexer: CellIndexer,
    private val hub: VehicleLocationHub,
    private val buffer: VehicleLocationBuffer,
    private val proximityAlertService: ProximityAlertService,
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
        val companyId = vehicleRepository.findById(activeAssignment.vehicleId)?.companyId

        // Accumulated as points are accepted, then handed to proximityAlertService once for the
        // whole batch below — the plausibility check itself must stay sequential (each point's
        // verdict depends on the previous accepted point's published position), but the proximity
        // candidate lookup doesn't need repeating per point, only the points to check it against.
        val acceptedPoints = mutableListOf<GeoPoint>()

        points.forEach { point ->
            val previous = hub.lastKnownLocation(activeAssignment.vehicleId)
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
                )
            hub.publish(update)
            buffer.enqueue(update)
            acceptedPoints += GeoPoint(point.latitude, point.longitude)
        }

        if (companyId != null) {
            proximityAlertService.evaluateBatch(activeAssignment.vehicleId, companyId, acceptedPoints)
        }

        return AppResult.Success(Unit)
    }
}
