/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import java.time.Instant

/** Persistence operations for assignment-scoped vehicle location history. */
interface VehicleLocationRepository {
    /** Persists a batch of location updates. */
    suspend fun insertAll(updates: List<VehicleLocationUpdate>)

    /**
     * Background persistence may outlive the vehicle, user, or assignment that produced a point.
     * Insert rows whose current foreign-key relationships still match and return how many stale or
     * malformed history copies were skipped.
     */
    suspend fun insertAllForCurrentReferences(updates: List<VehicleLocationUpdate>): Int

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
