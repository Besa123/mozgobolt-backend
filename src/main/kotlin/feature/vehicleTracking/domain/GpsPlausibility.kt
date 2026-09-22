package com.mozgobolt.feature.vehicleTracking.domain

import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import java.time.Duration
import java.time.Instant

private const val MILLIS_PER_HOUR = 3_600_000.0

/**
 * Guards against a classic GPS-hardware artifact: a brief loss of signal (a tunnel, an
 * underground garage) followed by reacquisition can report a wildly inaccurate fix for a few
 * seconds, which would otherwise show the vehicle teleporting on the live map. This rejects a
 * point only when it implies a physically impossible speed since the vehicle's last known
 * position — deliberately generous (see [MAX_PLAUSIBLE_SPEED_KMH]) so it never second-guesses
 * genuine fast driving, only outright teleports.
 */
object GpsPlausibility {
    const val MAX_PLAUSIBLE_SPEED_KMH = 200.0

    // How far apart two points may be when their ordering is ambiguous (a non-positive elapsed
    // time — a batching quirk, not a GPS artifact) for the later one to still count as jitter
    // around the same real position, not a genuinely different one. Deliberately generous, same
    // spirit as MAX_PLAUSIBLE_SPEED_KMH — this only needs to catch outright teleports, not
    // second-guess ordinary GPS noise.
    const val MAX_PLAUSIBLE_JITTER_DISTANCE_KM = 0.5

    fun isPlausibleMovement(
        previous: VehicleLocationUpdate?,
        candidateLatitude: Double,
        candidateLongitude: Double,
        candidateRecordedAt: Instant,
    ): Boolean {
        if (previous == null) return true // nothing to compare a vehicle's first-ever ping against

        val distanceKm = haversineKm(previous.latitude, previous.longitude, candidateLatitude, candidateLongitude)
        val elapsed = Duration.between(previous.recordedAt, candidateRecordedAt)

        // A non-positive elapsed time means there's no meaningful speed to compute, but a large
        // implied jump is still physically impossible regardless of ordering ambiguity — a vehicle
        // cannot really be far from where it just was at (about) the same instant. Only a
        // jitter-scale distance gets the benefit of the doubt here.
        return if (elapsed.isZero || elapsed.isNegative) {
            distanceKm <= MAX_PLAUSIBLE_JITTER_DISTANCE_KM
        } else {
            distanceKm / (elapsed.toMillis() / MILLIS_PER_HOUR) <= MAX_PLAUSIBLE_SPEED_KMH
        }
    }
}
