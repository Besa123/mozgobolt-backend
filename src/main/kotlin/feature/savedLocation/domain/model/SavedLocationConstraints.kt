package com.mozgobolt.feature.savedLocation.domain.model

/**
 * Deliberately separate from `MAX_RADIUS_KM` in
 * [com.mozgobolt.feature.vehicleTracking.routing]'s `LiveRequestParser`: that one bounds how far
 * a live map viewport query fans out *right now*; this one bounds how far a background "notify
 * me" geofence radius can reach around a saved point. Different concepts that happen to share
 * units — no reason they must share a limit, and a slightly larger one is reasonable here since a
 * background notification has time to reach the buyer before the vehicle arrives.
 */
object SavedLocationConstraints {
    const val MIN_RADIUS_KM = 0.1
    const val MAX_RADIUS_KM = 50.0
}
