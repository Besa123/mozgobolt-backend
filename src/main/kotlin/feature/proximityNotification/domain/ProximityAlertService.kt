package com.mozgobolt.feature.proximityNotification.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint

interface ProximityAlertService {
    /**
     * Checks whether any of [points] — telemetry recorded for a vehicle belonging to [companyId]
     * during one ingestion batch — should trigger an "a vehicle you follow is nearby" push to any
     * buyer who favorited that company and has a saved location near one of them. The
     * favoriting-buyers/saved-locations candidate set is resolved once for the whole batch, not
     * once per point — every point in a batch shares the same [companyId] (a batch's points all
     * belong to the same active assignment's vehicle, which can't change mid-batch), so refetching
     * it per point would just be the same query repeated with the same answer. Never throws — a
     * failure here (a bad candidate row, a push delivery failure) must never fail the telemetry
     * ingestion request that triggered it; see
     * [com.mozgobolt.feature.proximityNotification.service.ProximityAlertServiceI] for how that's
     * enforced.
     */
    suspend fun evaluateBatch(
        vehicleId: Int,
        companyId: Int,
        points: List<GeoPoint>,
    )
}
