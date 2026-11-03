/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_LATITUDE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_LONGITUDE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_SENT_AT
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_TYPE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_VEHICLE_ID
import com.mozgobolt.core.domain.push.PushEventType
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingOutcome
import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

// A rate limit, not a correctness invariant — long enough that a buyer can't spam the same
// vehicle, short enough that a genuine change of mind ("actually, wait — yes I do want one")
// isn't blocked for an unreasonable stretch. Internal (not private), the same way
// LiveRequestParser's MAX_RADIUS_KM is, so tests can assert the exact cooldown boundary rather
// than approximating it.
internal val PING_COOLDOWN = 5.minutes

// Privacy coarsening for the buyer's location on a ping — a different concern from
// H3CellIndexer.DEFAULT_RESOLUTION (tuned for the live-map's search-radius query), so it gets its
// own, deliberately finer, resolution. H3 resolution 9 has an average hexagon edge length of
// ~200m (confirmed against H3Core.getHexagonEdgeLengthAvg in H3CellIndexerTest, not trusted from
// memory), so a driver learns "this buyer is somewhere in this ~350-400m-wide area," never an
// exact point. Internal, not private, so tests can construct the same resolution the service
// actually uses.
internal const val PING_LOCATION_RESOLUTION = 9

class VehiclePingServiceI(
    private val pingRepository: VehiclePingRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleAssignmentService: VehicleAssignmentService,
    private val pingHub: VehiclePingHub,
    private val deviceInstallationService: DeviceInstallationService,
    private val cellIndexer: CellIndexer,
    private val tx: TransactionalRunner,
    private val clock: Clock = Clock.systemUTC(),
) : VehiclePingService {
    override suspend fun ping(
        buyerUserId: Int,
        vehicleId: Int,
        latitude: Double,
        longitude: Double,
    ): AppResult<VehiclePingOutcome, PingError> {
        // The exact coordinate is coarsened here, first, before touching any repository, log
        // line, or outgoing payload — approxLocation is the only location value that exists
        // anywhere past this line. Never widen this function's scope to pass the raw
        // latitude/longitude parameters any further than this.
        val approxLocation = cellIndexer.centerOf(cellIndexer.cellFor(latitude, longitude))

        val result: AppResult<VehiclePingOutcome, PingError> =
            tx.transactional {
                // This stable parent-row lock serializes cooldown checks even before a ping row
                // exists, and shares the vehicle lock used by assignment/archive mutations.
                val vehicle =
                    vehicleRepository
                        .findByIdForUpdate(vehicleId)
                        ?: return@transactional AppResult.Error(PingError.VEHICLE_NOT_FOUND)
                if (vehicle.archivedAt != null) {
                    return@transactional AppResult.Error(PingError.VEHICLE_NOT_ACTIVE)
                }

                val activeAssignment =
                    vehicleAssignmentService.findActiveForVehicle(vehicleId)
                        ?: return@transactional AppResult.Error(PingError.VEHICLE_NOT_ACTIVE)

                // Cooldown is scoped to the buyer and vehicle, not the current driver, so changing
                // assignments cannot reset it. The vehicle lock serializes concurrent checks.
                val lastSentAt = pingRepository.lastSentAt(vehicleId, buyerUserId)
                val now = clock.instant()
                if (lastSentAt != null && now.isBefore(lastSentAt.plus(PING_COOLDOWN.toJavaDuration()))) {
                    return@transactional AppResult.Error(PingError.COOLDOWN_ACTIVE)
                }

                // Never persisted, on purpose: even the coarsened point is only needed for the
                // two transient delivery paths below (SSE + push), not as durable history tied to
                // a buyer. vehicle_pings stays exactly what it was before this feature — no
                // location column at all.
                val ping = pingRepository.recordLatest(vehicleId, buyerUserId, now)
                AppResult.Success(
                    VehiclePingOutcome(
                        ping = ping,
                        vendorUserId = activeAssignment.vendorUserId,
                        notification =
                            VehiclePingNotification(
                                vehicleId = vehicleId,
                                sentAt = ping.sentAt,
                                latitude = approxLocation.latitude,
                                longitude = approxLocation.longitude,
                            ),
                    ),
                )
            }

        return result
    }

    override suspend fun dispatchPing(outcome: VehiclePingOutcome) {
        runSuspendCatching {
            pingHub.publish(outcome.vendorUserId, outcome.notification)
        }.onFailure { failure ->
            logPostCommitDeliveryFailure(
                channel = "live ping",
                vendorUserId = outcome.vendorUserId,
                vehicleId = outcome.ping.vehicleId,
                failure = failure,
            )
        }
        runSuspendCatching {
            notifyVendorViaPush(outcome.vendorUserId, outcome.notification)
        }.onFailure { failure ->
            logPostCommitDeliveryFailure(
                channel = "push",
                vendorUserId = outcome.vendorUserId,
                vehicleId = outcome.ping.vehicleId,
                failure = failure,
            )
        }
    }

    private suspend fun notifyVendorViaPush(
        vendorUserId: Int,
        notification: VehiclePingNotification,
    ) {
        val data =
            mapOf(
                PUSH_DATA_KEY_TYPE to PushEventType.PING.name,
                PUSH_DATA_KEY_VEHICLE_ID to notification.vehicleId.toString(),
                PUSH_DATA_KEY_SENT_AT to notification.sentAt.toString(),
                PUSH_DATA_KEY_LATITUDE to notification.latitude.toString(),
                PUSH_DATA_KEY_LONGITUDE to notification.longitude.toString(),
            )

        deviceInstallationService.sendPush(vendorUserId, data)
    }

    private fun logPostCommitDeliveryFailure(
        channel: String,
        vendorUserId: Int,
        vehicleId: Int,
        failure: Throwable,
    ) {
        logger.error {
            "Post-commit $channel delivery failed for vehicle $vehicleId to vendor $vendorUserId " +
                "(${failure.javaClass.simpleName}); ping remains accepted"
        }
    }
}
