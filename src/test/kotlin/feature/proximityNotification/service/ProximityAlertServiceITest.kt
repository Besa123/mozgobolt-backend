package com.mozgobolt.feature.proximityNotification.service

import com.mozgobolt.core.data.push.FakePushNotificationSender
import com.mozgobolt.core.data.push.InvalidPushTargetException
import com.mozgobolt.core.data.push.PermanentPushDeliveryException
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.deviceInstallation.domain.FakeDeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.service.DeviceInstallationServiceI
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationRecord
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import com.mozgobolt.feature.vehicleTracking.domain.haversineKm
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.toJavaDuration

class ProximityAlertServiceITest {
    // Budapest, roughly — the exact coordinates don't matter, only the relative distances below.
    private val savedPoint = 47.4979 to 19.0402

    // A few dozen meters from savedPoint — well within any radiusKm used in this file.
    private val insidePoint = 47.4980 to 19.0403

    // Tens of km away — well outside any radiusKm used in this file.
    private val outsidePoint = 47.6000 to 19.4000

    private class FakeCompanyFavoriteRepository : CompanyFavoriteRepository {
        private val favoritingUserIdsByCompany = mutableMapOf<Int, MutableSet<Int>>()

        var findUserIdsFavoritingCompanyCallCount = 0
            private set

        fun seedFavorite(
            companyId: Int,
            userId: Int,
        ) {
            favoritingUserIdsByCompany.getOrPut(companyId) { mutableSetOf() }.add(userId)
        }

        override suspend fun addIfAbsent(
            userId: Int,
            companyId: Int,
        ) = error("not exercised by this test")

        override suspend fun remove(
            userId: Int,
            companyId: Int,
        ) = error("not exercised by this test")

        override suspend fun findAllForUser(userId: Int) = error("not exercised by this test")

        override suspend fun findUserIdsFavoritingCompany(companyId: Int): List<Int> {
            findUserIdsFavoritingCompanyCallCount++
            return favoritingUserIdsByCompany[companyId]?.toList() ?: emptyList()
        }
    }

    private class FakeSavedLocationRepository : SavedLocationRepository {
        private val locationsById = mutableMapOf<Int, UserSavedLocation>()
        private var nextId = 1

        var findAllForUsersCallCount = 0
            private set

        fun seed(
            userId: Int,
            label: String,
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): UserSavedLocation {
            val location =
                UserSavedLocation(
                    id = nextId++,
                    userId = userId,
                    label = label,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    createdAt = Instant.now(),
                )
            locationsById[location.id] = location
            return location
        }

        override suspend fun create(
            userId: Int,
            label: String,
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ) = error("not exercised by this test")

        override suspend fun findById(id: Int) = error("not exercised by this test")

        override suspend fun findAllForUser(userId: Int) = error("not exercised by this test")

        override suspend fun delete(id: Int) = error("not exercised by this test")

        override suspend fun findAllForUsers(userIds: Collection<Int>): List<UserSavedLocation> {
            findAllForUsersCallCount++
            if (userIds.isEmpty()) return emptyList()
            return locationsById.values.filter { it.userId in userIds }
        }
    }

    /** In-memory but faithful to the real repository's find-then-write contract — see
     * [ProximityNotificationRepositoryI]'s kdoc for why that's an accepted race, not a bug. */
    private class FakeProximityNotificationRepository : ProximityNotificationRepository {
        private val records = mutableMapOf<Triple<Int, Int, Int>, ProximityNotificationRecord>()
        private var nextId = 1

        override suspend fun find(
            buyerUserId: Int,
            savedLocationId: Int,
            vehicleId: Int,
        ): ProximityNotificationRecord? = records[Triple(buyerUserId, savedLocationId, vehicleId)]

        override suspend fun recordEntryNotified(
            buyerUserId: Int,
            savedLocationId: Int,
            vehicleId: Int,
            notifiedAt: Instant,
        ): ProximityNotificationRecord {
            val key = Triple(buyerUserId, savedLocationId, vehicleId)
            val existing = records[key]
            val record =
                ProximityNotificationRecord(
                    id = existing?.id ?: nextId++,
                    buyerUserId = buyerUserId,
                    savedLocationId = savedLocationId,
                    vehicleId = vehicleId,
                    currentlyInside = true,
                    lastNotifiedAt = notifiedAt,
                )
            records[key] = record
            return record
        }

        override suspend fun markOutside(
            buyerUserId: Int,
            savedLocationId: Int,
            vehicleId: Int,
        ) {
            val key = Triple(buyerUserId, savedLocationId, vehicleId)
            records[key]?.let { records[key] = it.copy(currentlyInside = false) }
        }
    }

    private class Fixture {
        val favoriteRepository = FakeCompanyFavoriteRepository()
        val savedLocationRepository = FakeSavedLocationRepository()
        val proximityNotificationRepository = FakeProximityNotificationRepository()
        val deviceInstallationRepository = FakeDeviceInstallationRepository()
        val pushSender = FakePushNotificationSender()
        val deviceInstallationService =
            DeviceInstallationServiceI(deviceInstallationRepository, NoopTransactionalRunner(), pushSender)
        val service =
            ProximityAlertServiceI(
                companyFavoriteRepository = favoriteRepository,
                savedLocationRepository = savedLocationRepository,
                proximityNotificationRepository = proximityNotificationRepository,
                deviceInstallationService = deviceInstallationService,
                tx = NoopTransactionalRunner(),
            )
    }

    @Test
    fun `a vehicle entering a favoriting buyer's saved-location radius sends exactly one push`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            val (insideLat, insideLon) = insidePoint

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertEquals(1, fx.pushSender.sent.size)
            assertEquals(
                "buyer-device",
                fx.pushSender.sent
                    .single()
                    .installationId,
            )
        }
    }

    @Test
    fun `a buyer who never favorited the vehicle's company is never checked, not even the distance math`() {
        runBlocking {
            val fx = Fixture()
            // No favorite seeded for company 1 at all.
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = 47.4979,
                longitude = 19.0402,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            val (insideLat, insideLon) = insidePoint

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertTrue(fx.pushSender.sent.isEmpty())
            assertEquals(
                0,
                fx.savedLocationRepository.findAllForUsersCallCount,
                "no favoriting buyers means the saved-location query, and all distance math, must never even run",
            )
        }
    }

    @Test
    fun `a favoriting buyer with no saved locations is never pushed to`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            // No saved location seeded for user 10.
            val (insideLat, insideLon) = insidePoint

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertTrue(fx.pushSender.sent.isEmpty())
        }
    }

    @Test
    fun `a point outside the saved location's radius never sends a push`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            val (outsideLat, outsideLon) = outsidePoint

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = outsideLat, longitude = outsideLon)

            assertTrue(fx.pushSender.sent.isEmpty())
        }
    }

    @Test
    fun `a distance exactly equal to the saved location's radius counts as inside`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            val (pointLat, pointLon) = insidePoint
            // Setting radiusKm to exactly the production Haversine distance between these two
            // points guarantees `distanceKm <= radiusKm` is a true equality, not an approximation
            // — this exercises the "<=" boundary itself, not merely "comfortably under."
            val exactDistanceKm = haversineKm(pointLat, pointLon, savedLat, savedLon)
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = exactDistanceKm,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = pointLat, longitude = pointLon)

            assertEquals(1, fx.pushSender.sent.size, "a point exactly at the radius boundary must count as inside")
        }
    }

    @Test
    fun `staying inside the radius does not refire before the cooldown elapses`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            val (insideLat, insideLon) = insidePoint
            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)
            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertEquals(1, fx.pushSender.sent.size, "still inside, well within the cooldown — must not refire")
        }
    }

    @Test
    fun `leaving and re-entering fires again even within the cooldown window`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            val (insideLat, insideLon) = insidePoint
            val (outsideLat, outsideLon) = outsidePoint
            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = outsideLat, longitude = outsideLon)
            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertEquals(
                2,
                fx.pushSender.sent.size,
                "the re-entry must renotify despite being well within the cooldown",
            )
        }
    }

    @Test
    fun `staying inside long enough for the cooldown to elapse renotifies without ever leaving`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            val savedLocation =
                fx.savedLocationRepository.seed(
                    userId = 10,
                    label = "Home",
                    latitude = savedLat,
                    longitude = savedLon,
                    radiusKm = 1.0,
                )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            // Simulate "already notified a while ago, and still inside" directly, rather than
            // waiting real time in the test.
            val longAgo = Instant.now().minus(PROXIMITY_NOTIFICATION_COOLDOWN.toJavaDuration()).minusSeconds(1)
            fx.proximityNotificationRepository.recordEntryNotified(10, savedLocation.id, 100, longAgo)
            val (insideLat, insideLon) = insidePoint

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertEquals(1, fx.pushSender.sent.size, "cooldown elapsed while continuously inside — should renotify")
        }
    }

    @Test
    fun `staying inside just before the cooldown elapses does not renotify`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            val savedLocation =
                fx.savedLocationRepository.seed(
                    userId = 10,
                    label = "Home",
                    latitude = savedLat,
                    longitude = savedLon,
                    radiusKm = 1.0,
                )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            val justUnderCooldownAgo =
                Instant
                    .now()
                    .minus(
                        PROXIMITY_NOTIFICATION_COOLDOWN.toJavaDuration(),
                    ).plusSeconds(1)
            fx.proximityNotificationRepository.recordEntryNotified(10, savedLocation.id, 100, justUnderCooldownAgo)
            val (insideLat, insideLon) = insidePoint

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertTrue(fx.pushSender.sent.isEmpty(), "cooldown hasn't elapsed yet — must not renotify")
        }
    }

    @Test
    fun `a buyer with multiple saved locations is tracked independently per location`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (insideLat, insideLon) = insidePoint
            // "Home" is near the telemetry point; "Work" is far away.
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = insideLat,
                longitude = insideLon,
                radiusKm = 1.0,
            )
            val (outsideLat, outsideLon) = outsidePoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Work",
                latitude = outsideLat,
                longitude = outsideLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            // Only "Home" should have fired — one push, not two, and not zero.
            assertEquals(1, fx.pushSender.sent.size)
        }
    }

    @Test
    fun `evaluateBatch fetches favorites and saved locations once per batch, regardless of point count`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            val (insideLat, insideLon) = insidePoint
            val (outsideLat, outsideLon) = outsidePoint

            fx.service.evaluateBatch(
                vehicleId = 100,
                companyId = 1,
                points =
                    listOf(
                        GeoPoint(insideLat, insideLon),
                        GeoPoint(outsideLat, outsideLon),
                        GeoPoint(insideLat, insideLon),
                    ),
            )

            assertEquals(
                1,
                fx.favoriteRepository.findUserIdsFavoritingCompanyCallCount,
                "one batch of three points must fetch the favoriting-buyers candidate set exactly once",
            )
            assertEquals(
                1,
                fx.savedLocationRepository.findAllForUsersCallCount,
                "one batch of three points must fetch the saved-locations candidate set exactly once",
            )
            // Still checked against every point individually — the fix removes redundant queries,
            // not redundant distance checks. Point 1 (inside) notifies; point 2 (outside) marks
            // the location as left; point 3 (inside again) is a re-entry, which always renotifies
            // regardless of cooldown (see PROXIMITY_NOTIFICATION_COOLDOWN's own kdoc) — so two
            // pushes total, not one.
            assertEquals(2, fx.pushSender.sent.size)
        }
    }

    @Test
    fun `a push failure never propagates out of evaluate`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "buyer-device")
            fx.pushSender.failNextSendTo(
                "buyer-device",
                PermanentPushDeliveryException("fcm rejected the request shape"),
            )
            val (insideLat, insideLon) = insidePoint

            // Must not throw — a real bug here would have crashed the telemetry-ingestion request
            // that calls this.
            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)
        }
    }

    @Test
    fun `an invalid device token self-heals out on a proximity push failure`() {
        runBlocking {
            val fx = Fixture()
            fx.favoriteRepository.seedFavorite(companyId = 1, userId = 10)
            val (savedLat, savedLon) = savedPoint
            fx.savedLocationRepository.seed(
                userId = 10,
                label = "Home",
                latitude = savedLat,
                longitude = savedLon,
                radiusKm = 1.0,
            )
            fx.deviceInstallationRepository.seed(userId = 10, installationId = "stale-device")
            fx.pushSender.failNextSendTo("stale-device", InvalidPushTargetException("token gone"))
            val (insideLat, insideLon) = insidePoint

            fx.service.evaluate(vehicleId = 100, companyId = 1, latitude = insideLat, longitude = insideLon)

            assertTrue(fx.deviceInstallationRepository.findAllForUser(10).isEmpty(), "stale token should self-heal out")
        }
    }
}
