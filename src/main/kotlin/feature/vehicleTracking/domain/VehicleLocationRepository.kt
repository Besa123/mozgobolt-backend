package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import java.time.Instant

interface VehicleLocationRepository {
    suspend fun insertAll(updates: List<VehicleLocationUpdate>)

    /**
     * Deletes every row whose driving session ended before [cutoff], plus (defensively) any row
     * with no `assignmentId` at all — shouldn't occur given ingestion's own guarantee that a
     * point is never accepted without an active assignment, but falls back to the row's own
     * `recordedAt` against the same cutoff so nothing could accumulate unboundedly even in that
     * edge case. A still-active session (`endedAt == null`) is never purged, no matter how old
     * its points are. Returns the number of rows deleted.
     */
    suspend fun purgeOlderThan(cutoff: Instant): Int
}
