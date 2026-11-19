/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_COMPANY_ID
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_SAVED_LOCATION_ID
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_SAVED_LOCATION_LABEL
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_TYPE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_VEHICLE_ID
import com.mozgobolt.core.domain.push.PushEventType
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertCandidateRepository
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationCandidate
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.vehicleTracking.domain.haversineKm
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.asFlow
import java.time.Instant
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

private const val CONSERVATIVE_KM_PER_LATITUDE_DEGREE = 110.0

// Deliberately longer than VehiclePingServiceI's PING_COOLDOWN (5 minutes): a ping is a direct,
// time-sensitive signal between two people who both just acted; this is a passive background
// reminder about a vehicle that may simply be dwelling in the area for a while (e.g. parked at a
// market for an hour) — renotifying every few minutes for that would be exactly the notification
// spam this feature exists to avoid. Internal, not private, so tests can assert the exact
// boundary the same way LiveRequestParser's MAX_RADIUS_KM and VehiclePingServiceI's PING_COOLDOWN
// already do.
internal val PROXIMITY_NOTIFICATION_COOLDOWN = 30.minutes

/**
 * Matches a vehicle's live telemetry against the saved locations of buyers who favorited its
 * company, and pushes "it's nearby" to anyone whose watched area the vehicle just entered.
 *
 * Candidate narrowing (see [evaluateBatch]) runs one indexed join query per batch, before any
 * distance math. A buyer who never favorited this company, or has no saved locations, is never even
 * considered — this is deliberately not an H3-covering-cell index over saved locations, which
 * would be solving for a scale (many thousands of saved locations) this app doesn't have yet.
 *
 * Candidates a point is outside of (by far the common case) are marked with one statement per point,
 * not one transaction per candidate: otherwise telemetry latency would grow with the number of buyers
 * watching the company (measured at 34 transactions per telemetry request before, 10 after).
 *
 * [evaluateBatch] never throws: it is called synchronously from the telemetry-ingestion path
 * ([com.mozgobolt.feature.vehicleTracking.service.VehicleTrackingServiceI]), and a failure here —
 * a bad candidate row, a push delivery failure — must never fail that request. Every failure is
 * caught and logged, never propagated.
 */
class ProximityAlertServiceI(
    private val candidateRepository: ProximityAlertCandidateRepository,
    private val proximityNotificationRepository: ProximityNotificationRepository,
    private val deviceInstallationService: DeviceInstallationService,
    private val tx: TransactionalRunner,
) : ProximityAlertService {
    override suspend fun evaluateBatch(
        vehicleId: Int,
        companyId: Int,
        points: List<GeoPoint>,
    ) {
        if (points.isEmpty()) return

        runSuspendCatching {
            val candidates = tx.transactional { candidateRepository.findSavedLocationsForCompany(companyId) }
            if (candidates.isEmpty()) return@runSuspendCatching

            points.asFlow().collect { point ->
                val (inside, outside) = candidates.partition { it.covers(point) }
                markOutsideInOneWrite(vehicleId, outside)
                claimAndNotifyBatch(vehicleId, companyId, inside)
            }
        }.onError { throwable -> logger.warn(throwable) { "Proximity evaluation failed for vehicle $vehicleId" } }
    }

    /** Single-point convenience over [evaluateBatch] — not part of [ProximityAlertService], since
     * real ingestion always has a whole batch to evaluate at once; this exists for callers (tests)
     * that only care about one point at a time. */
    suspend fun evaluate(
        vehicleId: Int,
        companyId: Int,
        latitude: Double,
        longitude: Double,
    ) {
        evaluateBatch(vehicleId, companyId, listOf(GeoPoint(latitude, longitude)))
    }

    private suspend fun markOutsideInOneWrite(
        vehicleId: Int,
        outside: List<UserSavedLocation>,
    ) {
        if (outside.isEmpty()) return

        runSuspendCatching {
            tx.transactional { proximityNotificationRepository.markOutside(vehicleId, outside.map { it.id }) }
        }.onError { throwable ->
            logger.warn(throwable) { "Marking vehicle $vehicleId outside ${outside.size} saved locations failed" }
        }
    }

    private suspend fun claimAndNotifyBatch(
        vehicleId: Int,
        companyId: Int,
        savedLocations: List<UserSavedLocation>,
    ) {
        if (savedLocations.isEmpty()) return

        val attemptedAt = Instant.now()
        runSuspendCatching {
            tx.transactional {
                proximityNotificationRepository.claimEntryNotifications(
                    vehicleId = vehicleId,
                    candidates =
                        savedLocations.map { savedLocation ->
                            ProximityNotificationCandidate(
                                buyerUserId = savedLocation.userId,
                                savedLocationId = savedLocation.id,
                            )
                        },
                    attemptedAt = attemptedAt,
                    cooldownCutoff = attemptedAt.minus(PROXIMITY_NOTIFICATION_COOLDOWN.toJavaDuration()),
                )
            }
        }.foldSuspend(
            onSuccess = { claimedLocationIds ->
                notifyClaimedLocations(vehicleId, companyId, savedLocations, claimedLocationIds)
            },
            onError = { throwable ->
                logger.warn(throwable) {
                    "Proximity claim batch failed for ${savedLocations.size} saved locations, vehicle $vehicleId"
                }
            },
        )
    }

    private suspend fun notifyClaimedLocations(
        vehicleId: Int,
        companyId: Int,
        savedLocations: List<UserSavedLocation>,
        claimedLocationIds: Set<Int>,
    ) {
        savedLocations.forEach { savedLocation ->
            if (savedLocation.id !in claimedLocationIds) return@forEach
            runSuspendCatching {
                notifyBuyerViaPush(
                    buyerUserId = savedLocation.userId,
                    companyId = companyId,
                    vehicleId = vehicleId,
                    savedLocationId = savedLocation.id,
                    savedLocationLabel = savedLocation.label,
                )
            }.onError { throwable ->
                logger.warn(throwable) {
                    "Proximity push failed for saved location ${savedLocation.id}, vehicle $vehicleId"
                }
            }
        }
    }

    private suspend fun notifyBuyerViaPush(
        buyerUserId: Int,
        companyId: Int,
        vehicleId: Int,
        savedLocationId: Int,
        savedLocationLabel: String,
    ) {
        deviceInstallationService.sendPush(
            buyerUserId,
            mapOf(
                PUSH_DATA_KEY_TYPE to PushEventType.PROXIMITY_ALERT.name,
                PUSH_DATA_KEY_COMPANY_ID to companyId.toString(),
                PUSH_DATA_KEY_VEHICLE_ID to vehicleId.toString(),
                PUSH_DATA_KEY_SAVED_LOCATION_ID to savedLocationId.toString(),
                // A convenience, not a source of truth: the client already has this from
                // GET /saved-locations when the buyer created it, but re-sending it here means a
                // notification can render something meaningful even if that local cache is stale
                // or was never fetched (e.g. a fresh install restoring push before its first sync).
                PUSH_DATA_KEY_SAVED_LOCATION_LABEL to savedLocationLabel,
            ),
        )
    }
}

private fun UserSavedLocation.covers(point: GeoPoint): Boolean =
    abs(point.latitude - latitude) * CONSERVATIVE_KM_PER_LATITUDE_DEGREE <= radiusKm &&
        haversineKm(
            lat1 = point.latitude,
            lon1 = point.longitude,
            lat2 = latitude,
            lon2 = longitude,
        ) <= radiusKm
