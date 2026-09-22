package com.mozgobolt.feature.vehiclePing.domain

import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import kotlinx.coroutines.flow.Flow

/**
 * Fast path only: routes a single ping event to whichever vendor it targets, keyed by the
 * vendor's own user id rather than by vehicle — a vendor's currently-assigned vehicle can change
 * between pings, but a ping should still reach *them*, wherever they're currently connected.
 *
 * Unlike [com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub], there is no
 * persistence-backed "snapshot" here: a ping is transient, not state a newly-connected client
 * should catch up on. A vendor who isn't subscribed when [publish] fires simply misses it in this
 * phase — closing that gap is a push-notification concern, deliberately out of scope here.
 */
interface VehiclePingHub {
    fun publish(
        vendorUserId: Int,
        notification: VehiclePingNotification,
    )

    fun subscribe(vendorUserId: Int): Flow<VehiclePingNotification>
}
