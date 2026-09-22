package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_LATITUDE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_LONGITUDE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_SENT_AT
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_TYPE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_VEHICLE_ID
import com.mozgobolt.core.domain.push.PushEventType
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import com.mozgobolt.feature.vehicleTracking.domain.CellIndexer
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

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
) : VehiclePingService {
    @Suppress("ReturnCount")
    override suspend fun ping(
        buyerUserId: Int,
        vehicleId: Int,
        latitude: Double,
        longitude: Double,
    ): AppResult<VehiclePing, PingError> {
        // The exact coordinate is coarsened here, first, before touching any repository, log
        // line, or outgoing payload — approxLocation is the only location value that exists
        // anywhere past this line. Never widen this function's scope to pass the raw
        // latitude/longitude parameters any further than this.
        val approxLocation = cellIndexer.centerOf(cellIndexer.cellFor(latitude, longitude))

        val result: AppResult<PingOutcome, PingError> =
            tx.transactional {
                vehicleRepository.findById(vehicleId)
                    ?: return@transactional AppResult.Error(PingError.VEHICLE_NOT_FOUND)

                val activeAssignment =
                    vehicleAssignmentService.findActiveForVehicle(vehicleId)
                        ?: return@transactional AppResult.Error(PingError.VEHICLE_NOT_ACTIVE)

                // Check-then-insert, not DB-atomic: two genuinely concurrent pings from the same
                // buyer to the same vehicle could in theory both pass this check before either
                // commits. This is a rate limit, not a correctness invariant (unlike
                // vehicle_assignments' partial unique indexes) — accepted as a low-stakes race
                // rather than adding locking machinery for an abuse case this app's threat model
                // doesn't realistically have, the same reasoning already applied to the company's
                // last-admin guard elsewhere in this codebase. Scoped to (vehicleId, buyerUserId)
                // deliberately, not the vendor currently driving — the cooldown is "this buyer,
                // this vehicle," and must not reset just because the driver changed between two
                // pings.
                val lastSentAt = pingRepository.mostRecentSentAt(vehicleId, buyerUserId)
                val now = Instant.now()
                if (lastSentAt != null && now.isBefore(lastSentAt.plus(PING_COOLDOWN.toJavaDuration()))) {
                    return@transactional AppResult.Error(PingError.COOLDOWN_ACTIVE)
                }

                // Never persisted, on purpose: even the coarsened point is only needed for the
                // two transient delivery paths below (SSE + push), not as durable history tied to
                // a buyer. vehicle_pings stays exactly what it was before this feature — no
                // location column at all.
                val ping = pingRepository.create(vehicleId, buyerUserId, now)
                pingHub.publish(
                    activeAssignment.vendorUserId,
                    VehiclePingNotification(vehicleId, now, approxLocation.latitude, approxLocation.longitude),
                )

                AppResult.Success(PingOutcome(ping, activeAssignment.vendorUserId))
            }

        // Fire-and-forget, deliberately outside the DB transaction and after it commits: SSE
        // delivery via pingHub above is the primary, near-instant path when the vendor is
        // connected; this push is the fallback for when they aren't (app backgrounded/closed).
        // Sending both unconditionally — rather than first checking whether the vendor has a live
        // SSE subscriber — trades a rare, harmless redundant push for not having to track
        // per-vendor live-connection state; a push arriving a moment after an already-delivered
        // SSE event is a minor UX nit, not a correctness problem. A failure here must never turn
        // a successful ping into a failed HTTP response for the buyer.
        return result.fold(
            onSuccess = { (ping, vendorUserId) ->
                notifyVendorViaPush(vendorUserId, ping, approxLocation)
                AppResult.Success(ping)
            },
            onError = { AppResult.Error(it) },
        )
    }

    private suspend fun notifyVendorViaPush(
        vendorUserId: Int,
        ping: VehiclePing,
        approxLocation: GeoPoint,
    ) {
        val data =
            mapOf(
                PUSH_DATA_KEY_TYPE to PushEventType.PING.name,
                PUSH_DATA_KEY_VEHICLE_ID to ping.vehicleId.toString(),
                PUSH_DATA_KEY_SENT_AT to ping.sentAt.toString(),
                PUSH_DATA_KEY_LATITUDE to approxLocation.latitude.toString(),
                PUSH_DATA_KEY_LONGITUDE to approxLocation.longitude.toString(),
            )

        deviceInstallationService.sendPush(vendorUserId, data)
    }
}

/** What [VehiclePingServiceI.ping]'s transaction hands to the post-commit push step — the vendor
 * id doesn't survive on [VehiclePing] itself, so it travels alongside the created ping. */
private data class PingOutcome(
    val ping: VehiclePing,
    val vendorUserId: Int,
)
