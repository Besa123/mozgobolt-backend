package com.mozgobolt.feature.vehiclePing.domain.model

import java.time.Instant

/**
 * Deliberately anonymous: the vendor needs to know that a vehicle they're driving got pinged and
 * when, not who sent it. Exposing a buyer's identity to a vendor isn't needed for "someone wants
 * to buy something," and would be a needless privacy leak in the other direction — the buyer's
 * side of a ping is already anonymous-by-default in a face-to-face street-vendor interaction.
 *
 * [latitude]/[longitude] are already coarsened by the time they reach this type — see
 * [com.mozgobolt.feature.vehiclePing.service.VehiclePingServiceI]'s `PING_LOCATION_RESOLUTION` —
 * so the driver learns roughly where the buyer is (an area a few hundred meters across), never
 * their exact position.
 */
data class VehiclePingNotification(
    val vehicleId: Int,
    val sentAt: Instant,
    val latitude: Double,
    val longitude: Double,
)
