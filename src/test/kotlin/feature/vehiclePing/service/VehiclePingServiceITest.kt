package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.data.push.FakePushNotificationSender
import com.mozgobolt.core.data.push.InvalidPushTargetException
import com.mozgobolt.core.data.push.PermanentPushDeliveryException
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_LATITUDE
import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_LONGITUDE
import com.mozgobolt.feature.deviceInstallation.domain.FakeDeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.service.DeviceInstallationServiceI
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePingNotification
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.toJavaDuration

// Real Budapest coordinates, matching H3CellIndexerTest's convention — not a cell center, so
// tests asserting "the exact input never reaches the driver" have a genuine, non-trivial input.
private const val BUYER_LATITUDE = 47.4979
private const val BUYER_LONGITUDE = 19.0402

class VehiclePingServiceITest {
    private class FakeVehicleRepository : VehicleRepository {
        private val vehiclesById = mutableMapOf<Int, Vehicle>()

        fun seed(id: Int): Vehicle {
            val vehicle =
                Vehicle(
                    id = id,
                    companyId = 1,
                    label = "v$id",
                    licensePlate = "PLATE-$id",
                    pictureUrl = null,
                    createdAt = Instant.now(),
                )
            vehiclesById[id] = vehicle
            return vehicle
        }

        override suspend fun create(
            companyId: Int,
            label: String,
            licensePlate: String,
            pictureUrl: String?,
        ) = error("not exercised by this test")

        override suspend fun findById(vehicleId: Int): Vehicle? = vehiclesById[vehicleId]

        override suspend fun findAllByCompany(companyId: Int) = error("not exercised by this test")

        override suspend fun findByCompanyAndPlate(
            companyId: Int,
            licensePlate: String,
        ) = error("not exercised by this test")

        override suspend fun updateDetails(
            vehicleId: Int,
            licensePlate: String,
            pictureUrl: String?,
        ) = error("not exercised by this test")

        override suspend fun archive(vehicleId: Int) = error("not exercised by this test")

        override suspend fun updatePictureUrl(
            vehicleId: Int,
            pictureUrl: String?,
        ) = error("not exercised by this test")
    }

    /** Only [findActiveForVehicle] is exercised by [VehiclePingServiceI] — every other method
     * fails loudly if this test ever starts depending on it, so a future change to what the ping
     * service actually needs from this collaborator can't silently go untested. */
    private class FakeVehicleAssignmentService : VehicleAssignmentService {
        private val activeByVehicleId = mutableMapOf<Int, VehicleAssignment>()

        fun seedActive(
            vehicleId: Int,
            vendorUserId: Int,
        ) {
            activeByVehicleId[vehicleId] =
                VehicleAssignment(
                    id = vehicleId,
                    vehicleId = vehicleId,
                    vendorUserId = vendorUserId,
                    startedAt = Instant.now(),
                )
        }

        override suspend fun link(
            vendorUserId: Int,
            vehicleId: Int,
        ) = error("not exercised by this test")

        override suspend fun unlink(
            vendorUserId: Int,
            vehicleId: Int,
        ) = error("not exercised by this test")

        override suspend fun endActiveAssignment(
            adminUserId: Int,
            vehicleId: Int,
        ) = error("not exercised by this test")

        override suspend fun endActiveAssignmentForVendorInCompany(
            vendorUserId: Int,
            companyId: Int,
        ) = error("not exercised by this test")

        override suspend fun endAllActiveAssignmentsForCompany(companyId: Int) = error("not exercised by this test")

        override suspend fun findActiveForVendor(vendorUserId: Int) = error("not exercised by this test")

        override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? = activeByVehicleId[vehicleId]
    }

    /** In-memory but faithful to the real repository's contract, plus [simulateConcurrentReads]:
     * when set, [mostRecentSentAt] always reports "nothing yet," regardless of what has actually
     * been [create]d — simulating two requests whose reads both happened before either one's
     * insert became visible to the other. This is exactly the accepted race documented in
     * [VehiclePingServiceI]'s kdoc: the test below using this flag proves that race exists and
     * behaves as documented, not that it has been eliminated. */
    private class FakeVehiclePingRepository : VehiclePingRepository {
        private val pings = mutableListOf<VehiclePing>()
        private var nextId = 1L
        var simulateConcurrentReads = false

        override suspend fun mostRecentSentAt(
            vehicleId: Int,
            buyerUserId: Int,
        ): Instant? {
            if (simulateConcurrentReads) return null
            return pings
                .filter { it.vehicleId == vehicleId && it.buyerUserId == buyerUserId }
                .maxByOrNull { it.sentAt }
                ?.sentAt
        }

        override suspend fun create(
            vehicleId: Int,
            buyerUserId: Int,
            sentAt: Instant,
        ): VehiclePing {
            val ping = VehiclePing(id = nextId++, vehicleId = vehicleId, buyerUserId = buyerUserId, sentAt = sentAt)
            pings += ping
            return ping
        }

        fun seedPing(
            vehicleId: Int,
            buyerUserId: Int,
            sentAt: Instant,
        ) {
            pings += VehiclePing(id = nextId++, vehicleId = vehicleId, buyerUserId = buyerUserId, sentAt = sentAt)
        }
    }

    private class FakeVehiclePingHub : VehiclePingHub {
        val publishedTo = mutableListOf<Pair<Int, VehiclePingNotification>>()

        override fun publish(
            vendorUserId: Int,
            notification: VehiclePingNotification,
        ) {
            publishedTo += vendorUserId to notification
        }

        override fun subscribe(vendorUserId: Int) = error("not exercised by this test")
    }

    private class Fixture {
        val vehicleRepository = FakeVehicleRepository()
        val assignmentService = FakeVehicleAssignmentService()
        val pingRepository = FakeVehiclePingRepository()
        val hub = FakeVehiclePingHub()
        val deviceInstallationRepository = FakeDeviceInstallationRepository()
        val pushSender = FakePushNotificationSender()
        val deviceInstallationService =
            DeviceInstallationServiceI(deviceInstallationRepository, NoopTransactionalRunner(), pushSender)
        val cellIndexer = H3CellIndexer(resolution = PING_LOCATION_RESOLUTION)
        val service =
            VehiclePingServiceI(
                pingRepository = pingRepository,
                vehicleRepository = vehicleRepository,
                vehicleAssignmentService = assignmentService,
                pingHub = hub,
                deviceInstallationService = deviceInstallationService,
                cellIndexer = cellIndexer,
                tx = NoopTransactionalRunner(),
            )

        /** Every test that doesn't care about the coarsening itself pings from this fixed,
         * valid, real-world coordinate rather than repeating it at every call site. */
        suspend fun ping(
            buyerUserId: Int,
            vehicleId: Int,
            latitude: Double = BUYER_LATITUDE,
            longitude: Double = BUYER_LONGITUDE,
        ) = service.ping(buyerUserId, vehicleId, latitude, longitude)
    }

    @Test
    fun `pinging an active vehicle succeeds and notifies the currently-assigned vendor`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            val ping = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(10, ping.vehicleId)
            assertEquals(1, ping.buyerUserId)
            assertEquals(1, fx.hub.publishedTo.size)
            val (notifiedVendor, notification) = fx.hub.publishedTo.single()
            assertEquals(500, notifiedVendor)
            assertEquals(10, notification.vehicleId)
        }
    }

    @Test
    fun `pinging a vehicle that doesn't exist is rejected and nothing is published`() {
        runBlocking {
            val fx = Fixture()

            val result = fx.ping(buyerUserId = 1, vehicleId = 999)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(PingError.VEHICLE_NOT_FOUND, it)
            })
            assertTrue(fx.hub.publishedTo.isEmpty())
        }
    }

    @Test
    fun `pinging a vehicle with no active assignment is rejected and nothing is published`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_ACTIVE but got success") }, onError = {
                assertEquals(PingError.VEHICLE_NOT_ACTIVE, it)
            })
            assertTrue(fx.hub.publishedTo.isEmpty())
        }
    }

    @Test
    fun `a second ping from the same buyer to the same vehicle within the cooldown is rejected`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.ping(buyerUserId = 1, vehicleId = 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = { fail("expected COOLDOWN_ACTIVE but got success") }, onError = {
                assertEquals(PingError.COOLDOWN_ACTIVE, it)
            })
            assertEquals(1, fx.hub.publishedTo.size, "only the first, successful ping should have published anything")
        }
    }

    @Test
    fun `a different buyer pinging the same vehicle during another buyer's cooldown is not blocked`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.ping(buyerUserId = 1, vehicleId = 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.ping(buyerUserId = 2, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(2, fx.hub.publishedTo.size)
        }
    }

    @Test
    fun `the same buyer pinging a different vehicle is not blocked by the first vehicle's cooldown`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.vehicleRepository.seed(20)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.assignmentService.seedActive(vehicleId = 20, vendorUserId = 600)
            fx.ping(buyerUserId = 1, vehicleId = 10).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.ping(buyerUserId = 1, vehicleId = 20)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `a ping sent just before the cooldown elapses is rejected`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            val justUnderCooldownAgo = Instant.now().minus(PING_COOLDOWN.toJavaDuration()).plusSeconds(1)
            fx.pingRepository.seedPing(vehicleId = 10, buyerUserId = 1, sentAt = justUnderCooldownAgo)

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = { fail("expected COOLDOWN_ACTIVE but got success") }, onError = {
                assertEquals(PingError.COOLDOWN_ACTIVE, it)
            })
        }
    }

    @Test
    fun `a ping sent just after the cooldown has elapsed succeeds`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            val justOverCooldownAgo = Instant.now().minus(PING_COOLDOWN.toJavaDuration()).minusSeconds(1)
            fx.pingRepository.seedPing(vehicleId = 10, buyerUserId = 1, sentAt = justOverCooldownAgo)

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `changing the vehicle's active driver between two pings does not reset the buyer's cooldown`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.ping(buyerUserId = 1, vehicleId = 10).fold(onSuccess = {}, onError = { fail("$it") })
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 600) // a different vendor now drives it

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = { fail("expected COOLDOWN_ACTIVE but got success") }, onError = {
                assertEquals(PingError.COOLDOWN_ACTIVE, it)
            })
        }
    }

    @Test
    fun `REGRESSION two concurrent pings that both read no prior ping both succeed (the accepted race)`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.pingRepository.simulateConcurrentReads = true

            val first = fx.ping(buyerUserId = 1, vehicleId = 10)
            val second = fx.ping(buyerUserId = 1, vehicleId = 10)

            first.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            second.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(2, fx.hub.publishedTo.size, "this documents the accepted race, not a bug to fix")
        }
    }

    @Test
    fun `a successful ping pushes to every device installation registered for the currently-assigned vendor`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.deviceInstallationRepository.seed(userId = 500, installationId = "device-a")
            fx.deviceInstallationRepository.seed(userId = 500, installationId = "device-b")
            fx.deviceInstallationRepository.seed(userId = 999, installationId = "someone-elses-device")

            fx.ping(buyerUserId = 1, vehicleId = 10).fold(onSuccess = {}, onError = { fail("$it") })

            assertEquals(
                setOf("device-a", "device-b"),
                fx.pushSender.sent
                    .map { it.installationId }
                    .toSet(),
            )
        }
    }

    @Test
    fun `a ping that fails cooldown never triggers a push`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.deviceInstallationRepository.seed(userId = 500, installationId = "device-a")
            fx.ping(buyerUserId = 1, vehicleId = 10).fold(onSuccess = {}, onError = { fail("$it") })
            fx.pushSender.sent.clear()

            fx.ping(buyerUserId = 1, vehicleId = 10) // within cooldown, rejected

            assertTrue(fx.pushSender.sent.isEmpty())
        }
    }

    @Test
    fun `a vendor with no registered device installations is simply not pushed to`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertTrue(fx.pushSender.sent.isEmpty())
        }
    }

    @Test
    fun `a stale device installation that FCM rejects as invalid is deleted, and the ping still succeeds`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.deviceInstallationRepository.seed(userId = 500, installationId = "stale-device")
            fx.pushSender.failNextSendTo("stale-device", InvalidPushTargetException("target gone"))

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("a push failure must never fail the ping: $it") })
            assertTrue(
                fx.deviceInstallationRepository.findAllForUser(500).isEmpty(),
                "stale installation should self-heal out",
            )
        }
    }

    @Test
    fun `a non-invalid-target push failure does not delete the installation, and the ping still succeeds`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.deviceInstallationRepository.seed(userId = 500, installationId = "device-a")
            fx.pushSender.failNextSendTo(
                "device-a",
                PermanentPushDeliveryException("fcm rejected the request shape"),
            )

            val result = fx.ping(buyerUserId = 1, vehicleId = 10)

            result.fold(onSuccess = {}, onError = { fail("a push failure must never fail the ping: $it") })
            assertEquals(
                1,
                fx.deviceInstallationRepository.findAllForUser(500).size,
                "only invalid targets self-heal, not any failure",
            )
        }
    }

    @Test
    fun `two different exact coordinates in the same H3 cell produce the identical coarsened point`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            val cellOfBuyerOne = fx.cellIndexer.cellFor(BUYER_LATITUDE, BUYER_LONGITUDE)
            val center = fx.cellIndexer.centerOf(cellOfBuyerOne)
            // Precondition, not an assumption: the cell's own center must map back to the same
            // cell, otherwise this pair of coordinates wouldn't actually exercise "two different
            // inputs, one cell" and the test below would need a different second point.
            assertEquals(cellOfBuyerOne, fx.cellIndexer.cellFor(center.latitude, center.longitude))

            fx
                .ping(buyerUserId = 1, vehicleId = 10, latitude = BUYER_LATITUDE, longitude = BUYER_LONGITUDE)
                .fold(onSuccess = {}, onError = { fail("$it") })
            fx
                .ping(buyerUserId = 2, vehicleId = 10, latitude = center.latitude, longitude = center.longitude)
                .fold(onSuccess = {}, onError = { fail("$it") })

            val (_, firstNotification) = fx.hub.publishedTo[0]
            val (_, secondNotification) = fx.hub.publishedTo[1]
            assertEquals(firstNotification.latitude, secondNotification.latitude)
            assertEquals(firstNotification.longitude, secondNotification.longitude)
        }
    }

    @Test
    fun `the exact coordinate the buyer sent never reaches the driver's notification or push`() {
        runBlocking {
            val fx = Fixture()
            fx.vehicleRepository.seed(10)
            fx.assignmentService.seedActive(vehicleId = 10, vendorUserId = 500)
            fx.deviceInstallationRepository.seed(userId = 500, installationId = "device-a")

            fx
                .ping(buyerUserId = 1, vehicleId = 10, latitude = BUYER_LATITUDE, longitude = BUYER_LONGITUDE)
                .fold(onSuccess = {}, onError = { fail("$it") })

            val (_, notification) = fx.hub.publishedTo.single()
            assertNotEquals(BUYER_LATITUDE, notification.latitude)
            assertNotEquals(BUYER_LONGITUDE, notification.longitude)

            val push = fx.pushSender.sent.single()
            assertNotEquals(BUYER_LATITUDE.toString(), push.data[PUSH_DATA_KEY_LATITUDE])
            assertNotEquals(BUYER_LONGITUDE.toString(), push.data[PUSH_DATA_KEY_LONGITUDE])
            assertEquals(notification.latitude.toString(), push.data[PUSH_DATA_KEY_LATITUDE])
            assertEquals(notification.longitude.toString(), push.data[PUSH_DATA_KEY_LONGITUDE])
        }
    }
}
