package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

private val logger = KotlinLogging.logger {}

// Internal, not private, so tests can assert against the exact channel name rather than
// duplicating the literal — same convention as LiveRequestParser's MAX_RADIUS_KM.
internal const val VEHICLE_LOCATION_CHANNEL = "mozgobolt:vehicle-locations"

/**
 * Wire format for [VEHICLE_LOCATION_CHANNEL] — mirrors [com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent]
 * one-for-one, since every instance must learn about both a vehicle's positions *and* its going
 * offline, not just positions — otherwise only the instance that happened to handle the
 * unlink/end-session call would ever clear its own [InMemoryVehicleLocationHub.latestByVehicleId]
 * entry, leaving every other instance holding a stale entry forever.
 */
@Serializable
private sealed interface VehicleLocationRelayMessage {
    @Serializable
    data class Position(
        val vehicleId: Int,
        val vendorUserId: Int,
        val latitude: Double,
        val longitude: Double,
        val recordedAt: String,
        val cellId: String,
        val assignmentId: Int? = null,
    ) : VehicleLocationRelayMessage

    @Serializable
    data class Offline(
        val vehicleId: Int,
    ) : VehicleLocationRelayMessage
}

private fun VehicleLocationUpdate.toRelayMessage() =
    VehicleLocationRelayMessage.Position(
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt.toString(),
        cellId = cellId.value,
        assignmentId = assignmentId,
    )

private fun VehicleLocationRelayMessage.Position.toDomain() =
    VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = Instant.parse(recordedAt),
        cellId = CellId(cellId),
        assignmentId = assignmentId,
    )

/**
 * Decorates an in-memory [VehicleLocationHub] with cross-instance fanout over a [MessageRelay]:
 * every [publish] both updates this process's own local hub (for its own connected clients, exactly
 * as before) and relays the update to every other instance sharing the same relay; every relayed
 * update — including ones from other instances — is fed back into the local hub the same way, so
 * every instance's subscribers and snapshot cache converge to the same state regardless of which
 * instance a given vehicle's telemetry happened to land on.
 *
 * With a [com.mozgobolt.core.data.messaging.NoOpMessageRelay] (the single-instance default), the
 * relay side of both operations is a no-op, so this behaves exactly like the undecorated [delegate].
 *
 * [start]/[stop] are separate from the [VehicleLocationHub] interface (mirroring
 * [VehicleLocationBatchWriter]'s own shape) since they're a process lifecycle concern, not
 * something a route or service consuming [VehicleLocationHub] needs to know about.
 */
class RedisRelayVehicleLocationHub(
    private val delegate: VehicleLocationHub,
    private val relay: MessageRelay,
) : VehicleLocationHub by delegate {
    private val exceptionHandler =
        CoroutineExceptionHandler { _, failure ->
            logger.error(failure) { "RedisRelayVehicleLocationHub's relay-consuming loop terminated unexpectedly" }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var job: Job? = null

    override fun publish(update: VehicleLocationUpdate) {
        delegate.publish(update)
        relayMessage(update.toRelayMessage(), failureContext = "a vehicle location update")
    }

    override fun markOffline(vehicleId: Int) {
        delegate.markOffline(vehicleId)
        relayMessage(VehicleLocationRelayMessage.Offline(vehicleId), failureContext = "a vehicle offline signal")
    }

    private fun relayMessage(
        message: VehicleLocationRelayMessage,
        failureContext: String,
    ) {
        scope.launch {
            runSuspendCatching { relay.publish(VEHICLE_LOCATION_CHANNEL, Json.encodeToString(message)) }
                .onFailure { logger.warn(it) { "Failed to relay $failureContext; other instances may miss it" } }
        }
    }

    fun start() {
        job =
            scope.launch {
                relay.subscribe(VEHICLE_LOCATION_CHANNEL).collect { raw ->
                    runSuspendCatching { Json.decodeFromString<VehicleLocationRelayMessage>(raw) }
                        .onSuccess { message ->
                            when (message) {
                                is VehicleLocationRelayMessage.Position -> delegate.publish(message.toDomain())
                                is VehicleLocationRelayMessage.Offline -> delegate.markOffline(message.vehicleId)
                            }
                        }.onFailure {
                            logger.warn(
                                it,
                            ) { "Failed to process a relayed vehicle location update; skipping" }
                        }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
