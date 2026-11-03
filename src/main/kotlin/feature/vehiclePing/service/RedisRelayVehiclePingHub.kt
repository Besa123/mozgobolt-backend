/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

private val logger = KotlinLogging.logger {}

// Internal, not private, so tests can assert against the exact channel name rather than
// duplicating the literal — same convention as LiveRequestParser's MAX_RADIUS_KM.
internal const val VEHICLE_PING_CHANNEL = "mozgobolt:vehicle-pings"

@Serializable
private data class VehiclePingRelayMessage(
    val vendorUserId: Int,
    val vehicleId: Int,
    val sentAt: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Decorates an in-memory [VehiclePingHub] with cross-instance fanout over a [MessageRelay] — same
 * shape as [com.mozgobolt.feature.vehicleTracking.service.RedisRelayVehicleLocationHub], see its
 * kdoc for the full rationale. Here the relay carries [vendorUserId] alongside the notification,
 * since [VehiclePingHub.publish] takes it as a separate parameter rather than as part of
 * [VehiclePingNotification] itself — a receiving instance needs it to route into the right local,
 * per-vendor flow.
 */
class RedisRelayVehiclePingHub(
    private val delegate: VehiclePingHub,
    private val relay: MessageRelay,
    private val backgroundScope: ApplicationBackgroundScope,
) : VehiclePingHub by delegate {
    private var job: Job? = null

    override fun publish(
        vendorUserId: Int,
        notification: VehiclePingNotification,
    ) {
        delegate.publish(vendorUserId, notification)

        val relayMessage =
            VehiclePingRelayMessage(
                vendorUserId = vendorUserId,
                vehicleId = notification.vehicleId,
                sentAt = notification.sentAt.toString(),
                latitude = notification.latitude,
                longitude = notification.longitude,
            )
        backgroundScope.launch(CoroutineName("vehicle-ping-relay-publisher")) {
            runSuspendCatching { relay.publish(VEHICLE_PING_CHANNEL, Json.encodeToString(relayMessage)) }
                .onFailure { logger.warn(it) { "Failed to relay a ping; other instances may miss it" } }
        }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("vehicle-ping-relay-subscriber")) {
                relay.subscribe(VEHICLE_PING_CHANNEL).collect { raw ->
                    val decodeResult = runSuspendCatching { Json.decodeFromString<VehiclePingRelayMessage>(raw) }
                    decodeResult.onSuccess {
                        delegate.publish(
                            it.vendorUserId,
                            VehiclePingNotification(
                                vehicleId = it.vehicleId,
                                sentAt = Instant.parse(it.sentAt),
                                latitude = it.latitude,
                                longitude = it.longitude,
                            ),
                        )
                    }
                    decodeResult.onFailure { logger.warn(it) { "Failed to process a relayed ping; skipping" } }
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
