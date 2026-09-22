package com.mozgobolt.feature.proximityNotification.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_COMPANY_ID
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_SAVED_LOCATION_ID
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_SAVED_LOCATION_LABEL
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_TYPE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_VEHICLE_ID
import com.mozgobolt.core.domain.push.PushEventType
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.vehicleTracking.domain.haversineKm
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

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
 * Candidate narrowing (see [evaluateBatch]) always runs two small, indexed queries once per batch,
 * before any distance math: which buyers favorited this company, then which saved locations belong
 * to them. A buyer who never favorited this company, or has no saved locations, is never even
 * considered — this is deliberately not an H3-covering-cell index over saved locations, which would
 * be solving for a scale (many thousands of saved locations) this app doesn't have yet.
 *
 * [evaluateBatch] never throws: it is called synchronously from the telemetry-ingestion path
 * ([com.mozgobolt.feature.vehicleTracking.service.VehicleTrackingServiceI]), and a failure here —
 * a bad candidate row, a push delivery failure — must never fail that request. Every failure is
 * caught and logged, never propagated.
 */
class ProximityAlertServiceI(
    private val companyFavoriteRepository: CompanyFavoriteRepository,
    private val savedLocationRepository: SavedLocationRepository,
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
            val favoritingUserIds =
                tx.transactional { companyFavoriteRepository.findUserIdsFavoritingCompany(companyId) }
            if (favoritingUserIds.isEmpty()) return@runSuspendCatching

            val candidates = tx.transactional { savedLocationRepository.findAllForUsers(favoritingUserIds) }
            if (candidates.isEmpty()) return@runSuspendCatching

            points.forEach { point ->
                candidates.forEach { savedLocation ->
                    runSuspendCatching {
                        evaluateCandidate(vehicleId, companyId, point.latitude, point.longitude, savedLocation)
                    }.onFailure { throwable ->
                        logger.warn(throwable) {
                            "Proximity check failed for saved location ${savedLocation.id}, vehicle $vehicleId"
                        }
                    }
                }
            }
        }.onFailure { throwable -> logger.warn(throwable) { "Proximity evaluation failed for vehicle $vehicleId" } }
    }

    /** Single-point convenience over [evaluateBatch] — not part of [ProximityAlertService], since
     * real ingestion always has a whole batch to evaluate at once; this exists for callers (tests)
     * that only care about one point at a time. */
    suspend fun evaluate(
        vehicleId: Int,
        companyId: Int,
        latitude: Double,
        longitude: Double,
    ) = evaluateBatch(vehicleId, companyId, listOf(GeoPoint(latitude, longitude)))

    private suspend fun evaluateCandidate(
        vehicleId: Int,
        companyId: Int,
        latitude: Double,
        longitude: Double,
        savedLocation: UserSavedLocation,
    ) {
        val distanceKm = haversineKm(latitude, longitude, savedLocation.latitude, savedLocation.longitude)
        val isWithinRadius = distanceKm <= savedLocation.radiusKm

        if (!isWithinRadius) {
            tx.transactional {
                proximityNotificationRepository.markOutside(savedLocation.userId, savedLocation.id, vehicleId)
            }
            return
        }

        val now = Instant.now()
        val existing =
            tx.transactional { proximityNotificationRepository.find(savedLocation.userId, savedLocation.id, vehicleId) }

        // Renotify when this triple has never been seen inside before, or the vehicle left and
        // came back (existing.currentlyInside == false — re-entry always renotifies, regardless
        // of how little time has passed), or the vehicle has been continuously inside long enough
        // that a fresh reminder is reasonable (cooldown elapsed since the last notification).
        val cooldownElapsed =
            existing != null &&
                !now.isBefore(existing.lastNotifiedAt.plus(PROXIMITY_NOTIFICATION_COOLDOWN.toJavaDuration()))
        val shouldNotify = existing == null || !existing.currentlyInside || cooldownElapsed
        if (!shouldNotify) return

        tx.transactional {
            proximityNotificationRepository.recordEntryNotified(savedLocation.userId, savedLocation.id, vehicleId, now)
        }
        notifyBuyerViaPush(savedLocation.userId, companyId, vehicleId, savedLocation.id, savedLocation.label)
    }

    private suspend fun notifyBuyerViaPush(
        buyerUserId: Int,
        companyId: Int,
        vehicleId: Int,
        savedLocationId: Int,
        savedLocationLabel: String,
    ) {
        val data =
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
            )

        deviceInstallationService.sendPush(buyerUserId, data)
    }
}
