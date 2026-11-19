/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.CellId
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

// Internal, not private, so tests can assert against the exact channel name rather than
// duplicating the literal — same convention as LiveRequestParser's MAX_RADIUS_KM.
internal const val VEHICLE_LOCATION_CHANNEL = "mozgobolt:vehicle-locations"

@Serializable
private data class VehicleLocationRelayPosition(
    val vehicleId: Int,
    val vendorUserId: Int,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: String,
    val cellId: String,
    val assignmentId: Int?,
    val sharesLocationWithCustomers: Boolean,
    val idempotencyKey: String,
)

/**
 * Relay messages carry ordered position batches or assignment-scoped offline signals. Offline
 * signals must reach every instance; otherwise only the instance handling unlink/end-session
 * would clear its local [InMemoryVehicleLocationHub.latestByVehicleId] entry.
 */
@Serializable
private sealed interface VehicleLocationRelayMessage {
    @Serializable
    data class Batch(
        val positions: List<VehicleLocationRelayPosition>,
    ) : VehicleLocationRelayMessage

    @Serializable
    data class Offline(
        val vehicleId: Int,
        val assignmentId: Int? = null,
    ) : VehicleLocationRelayMessage
}

private fun VehicleLocationUpdate.toRelayPosition() =
    VehicleLocationRelayPosition(
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt.toString(),
        cellId = cellId.value,
        assignmentId = assignmentId,
        sharesLocationWithCustomers = sharesLocationWithCustomers,
        idempotencyKey = idempotencyKey.toString(),
    )

private fun VehicleLocationRelayPosition.toDomain() =
    VehicleLocationUpdate(
        vehicleId = vehicleId,
        vendorUserId = vendorUserId,
        latitude = latitude,
        longitude = longitude,
        recordedAt = Instant.parse(recordedAt),
        cellId = CellId(cellId),
        assignmentId = assignmentId,
        sharesLocationWithCustomers = sharesLocationWithCustomers,
        idempotencyKey = UUID.fromString(idempotencyKey),
    )

/**
 * Decorates an in-memory [VehicleLocationHub] with cross-instance fanout over a [MessageRelay]:
 * every accepted update first reaches this process's local hub, then is relayed to other instances.
 * Every telemetry batch is sent as one relay message, avoiding a coroutine and Redis command per
 * point. Receiving instances apply positions in input order. Relayed updates are fed into the local
 * hub the same way, so each instance's subscribers and snapshot cache converge on incoming state.
 *
 * With a [com.mozgobolt.core.data.messaging.NoOpMessageRelay] (the single-instance default), the
 * relay side of both operations is a no-op, so this behaves exactly like the undecorated [delegate].
 *
 * [start]/[stop] are separate from the [VehicleLocationHub] interface. The collector job belongs
 * to the application's shared background scope, while [stop] remains available for explicitly
 * stopping this relay consumer.
 */
class RedisRelayVehicleLocationHub(
    private val delegate: VehicleLocationHub,
    private val relay: MessageRelay,
    private val backgroundScope: ApplicationBackgroundScope,
) : VehicleLocationHub by delegate {
    private var job: Job? = null

    override fun publish(update: VehicleLocationUpdate) {
        publishIfCurrent(update)
    }

    override fun publishIfCurrent(update: VehicleLocationUpdate): Boolean {
        if (!delegate.publishIfCurrent(update)) return false
        if (relay.isCrossInstanceRelaySupported) {
            relayMessage(
                VehicleLocationRelayMessage.Batch(listOf(update.toRelayPosition())),
                failureContext = "a vehicle location update",
            )
        }
        return true
    }

    override fun publishBatchIfCurrent(updates: List<VehicleLocationUpdate>): List<VehicleLocationUpdate> {
        val acceptedUpdates = updates.filter(delegate::publishIfCurrent)
        if (relay.isCrossInstanceRelaySupported && acceptedUpdates.isNotEmpty()) {
            val positions = acceptedUpdates.map(VehicleLocationUpdate::toRelayPosition)
            relayMessage(
                VehicleLocationRelayMessage.Batch(positions),
                failureContext = "a vehicle location batch",
            )
        }
        return acceptedUpdates
    }

    override fun markOffline(vehicleId: Int) {
        delegate.markOffline(vehicleId)
        if (relay.isCrossInstanceRelaySupported) {
            relayMessage(VehicleLocationRelayMessage.Offline(vehicleId), failureContext = "a vehicle offline signal")
        }
    }

    override fun markOffline(
        vehicleId: Int,
        assignmentId: Int,
    ): Boolean {
        if (!delegate.markOffline(vehicleId, assignmentId)) return false
        if (relay.isCrossInstanceRelaySupported) {
            relayMessage(
                VehicleLocationRelayMessage.Offline(vehicleId, assignmentId),
                failureContext = "a vehicle offline signal",
            )
        }
        return true
    }

    private fun relayMessage(
        message: VehicleLocationRelayMessage,
        failureContext: String,
    ) {
        backgroundScope.launch(CoroutineName("vehicle-location-relay-publisher")) {
            publishRelayMessage(message, failureContext)
        }
    }

    private suspend fun publishRelayMessage(
        message: VehicleLocationRelayMessage,
        failureContext: String,
    ) {
        runSuspendCatching { relay.publish(VEHICLE_LOCATION_CHANNEL, Json.encodeToString(message)) }
            .onError { logger.warn(it) { "Failed to relay $failureContext; other instances may miss it" } }
    }

    @Synchronized
    fun start() {
        if (!relay.isCrossInstanceRelaySupported || job?.isActive == true) return

        val updates = relay.subscribe(VEHICLE_LOCATION_CHANNEL)
        job =
            backgroundScope.launch(CoroutineName("vehicle-location-relay-subscriber")) {
                updates.collect { raw ->
                    val decodeResult = runSuspendCatching { Json.decodeFromString<VehicleLocationRelayMessage>(raw) }
                    decodeResult.onSuccess { message ->
                        when (message) {
                            is VehicleLocationRelayMessage.Batch -> {
                                message.positions.forEach { position ->
                                    delegate.publishIfCurrent(position.toDomain())
                                }
                            }

                            is VehicleLocationRelayMessage.Offline -> {
                                message.assignmentId?.let { assignmentId ->
                                    delegate.markOffline(message.vehicleId, assignmentId)
                                } ?: delegate.markOffline(message.vehicleId)
                            }
                        }
                    }
                    decodeResult.onError { failure ->
                        logger.warn(failure) { "Failed to process a relayed vehicle location update; skipping" }
                    }
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun awaitSubscriptionReady() {
        if (relay.isCrossInstanceRelaySupported) {
            relay.awaitSubscription(VEHICLE_LOCATION_CHANNEL)
        }
    }
}
