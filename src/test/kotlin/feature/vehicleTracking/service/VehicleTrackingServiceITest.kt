package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleTrackingServiceITest {
    private val budapest = 47.4979 to 19.0402
    private val debrecen = 47.5316 to 21.6273 // ~180km away — used to trigger an implausible jump

    private fun point(
        latitude: Double,
        longitude: Double,
        recordedAt: Instant,
    ) = TelemetryPoint(latitude, longitude, recordedAt)

    @Test
    fun `a vendor with no active vehicle assignment is rejected before touching the hub or buffer`() {
        runBlocking {
            val hub = FakeVehicleLocationHub()
            val buffer = FakeVehicleLocationBuffer()
            val service = newService(activeAssignment = null, hub = hub, buffer = buffer)
            val (lat, lon) = budapest

            val result = service.ingestTelemetry(vendorUserId = 1, points = listOf(point(lat, lon, Instant.now())))

            result.fold(
                onSuccess = { fail("expected NOT_LINKED_TO_VEHICLE but got success") },
                onError = { assertEquals(TelemetryError.NOT_LINKED_TO_VEHICLE, it) },
            )
            assertTrue(hub.published.isEmpty())
            assertTrue(buffer.enqueued.isEmpty())
        }
    }

    @Test
    fun `a plausible point is published to the hub and enqueued for the durable history`() {
        runBlocking {
            val hub = FakeVehicleLocationHub()
            val buffer = FakeVehicleLocationBuffer()
            val assignment = activeAssignment(vehicleId = 7)
            val service = newService(activeAssignment = assignment, hub = hub, buffer = buffer)
            val (lat, lon) = budapest

            val result = service.ingestTelemetry(vendorUserId = 1, points = listOf(point(lat, lon, Instant.now())))

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(1, hub.published.size)
            assertEquals(7, hub.published.single().vehicleId)
            // The retention purge scopes its cutoff to a session's ended_at, not each point's own
            // recordedAt — this only works if every point is actually tagged with the assignment
            // it was recorded during, sourced from the vendor's real active assignment, not
            // re-derived some other way.
            assertEquals(assignment.id, hub.published.single().assignmentId)
            assertEquals(1, buffer.enqueued.size)
            assertEquals(assignment.id, buffer.enqueued.single().assignmentId)
        }
    }

    @Test
    fun `an implausible GPS jump within a batch is dropped, the rest of the batch still lands`() {
        runBlocking {
            val hub = FakeVehicleLocationHub()
            val buffer = FakeVehicleLocationBuffer()
            val service = newService(activeAssignment = activeAssignment(vehicleId = 7), hub = hub, buffer = buffer)
            val (fromLat, fromLon) = budapest
            val (jumpLat, jumpLon) = debrecen
            val first = point(fromLat, fromLon, Instant.now())
            // ~180km 5 seconds later than `first` — a physically impossible jump.
            val implausibleJump = point(jumpLat, jumpLon, first.recordedAt.plusSeconds(5))
            val plausibleFollowUp = point(fromLat + 0.001, fromLon, first.recordedAt.plusSeconds(30))

            val result =
                service.ingestTelemetry(vendorUserId = 1, points = listOf(first, implausibleJump, plausibleFollowUp))

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            // Only `first` and `plausibleFollowUp` should have landed — the jump was dropped, not
            // just flagged, and it must not have poisoned the "last known" state used to judge the
            // point after it.
            assertEquals(2, hub.published.size)
            assertEquals(fromLat, hub.published[0].latitude)
            assertEquals(fromLat + 0.001, hub.published[1].latitude)
            assertEquals(2, buffer.enqueued.size)
        }
    }

    @Test
    fun `a plausible point triggers a proximity check for the vehicle's company`() {
        runBlocking {
            val hub = FakeVehicleLocationHub()
            val buffer = FakeVehicleLocationBuffer()
            val proximityAlertService = FakeProximityAlertService()
            val service =
                newService(
                    activeAssignment = activeAssignment(vehicleId = 7),
                    hub = hub,
                    buffer = buffer,
                    vehicleRepository = FakeVehicleRepository(companyIdByVehicleId = mapOf(7 to 42)),
                    proximityAlertService = proximityAlertService,
                )
            val (lat, lon) = budapest

            service
                .ingestTelemetry(vendorUserId = 1, points = listOf(point(lat, lon, Instant.now())))
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })

            assertEquals(1, proximityAlertService.evaluations.size)
            val evaluation = proximityAlertService.evaluations.single()
            assertEquals(7, evaluation.vehicleId)
            assertEquals(42, evaluation.companyId)
            assertEquals(lat, evaluation.latitude)
            assertEquals(lon, evaluation.longitude)
        }
    }

    @Test
    fun `a batch of several plausible points triggers exactly one proximity check, not one per point`() {
        runBlocking {
            val hub = FakeVehicleLocationHub()
            val buffer = FakeVehicleLocationBuffer()
            val proximityAlertService = FakeProximityAlertService()
            val service =
                newService(
                    activeAssignment = activeAssignment(vehicleId = 7),
                    hub = hub,
                    buffer = buffer,
                    vehicleRepository = FakeVehicleRepository(companyIdByVehicleId = mapOf(7 to 42)),
                    proximityAlertService = proximityAlertService,
                )
            val (lat, lon) = budapest
            val first = point(lat, lon, Instant.now())
            val second = point(lat + 0.001, lon, first.recordedAt.plusSeconds(30))
            val third = point(lat + 0.002, lon, first.recordedAt.plusSeconds(60))

            service
                .ingestTelemetry(vendorUserId = 1, points = listOf(first, second, third))
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })

            assertEquals(
                1,
                proximityAlertService.evaluateBatchCallCount,
                "the candidate-fetching queries inside evaluateBatch must run once per batch, not once per point",
            )
            assertEquals(3, proximityAlertService.evaluations.size, "all three points should still be checked")
        }
    }

    @Test
    fun `a dropped implausible point never triggers a proximity check`() {
        runBlocking {
            val hub = FakeVehicleLocationHub()
            val buffer = FakeVehicleLocationBuffer()
            val proximityAlertService = FakeProximityAlertService()
            val service =
                newService(
                    activeAssignment = activeAssignment(vehicleId = 7),
                    hub = hub,
                    buffer = buffer,
                    vehicleRepository = FakeVehicleRepository(companyIdByVehicleId = mapOf(7 to 42)),
                    proximityAlertService = proximityAlertService,
                )
            val (fromLat, fromLon) = budapest
            val (jumpLat, jumpLon) = debrecen
            val first = point(fromLat, fromLon, Instant.now())
            val implausibleJump = point(jumpLat, jumpLon, first.recordedAt.plusSeconds(5))

            service
                .ingestTelemetry(vendorUserId = 1, points = listOf(first, implausibleJump))
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })

            assertEquals(1, proximityAlertService.evaluations.size, "only the plausible point should be checked")
        }
    }

    @Test
    fun `a vehicle with no resolvable company never triggers a proximity check`() {
        runBlocking {
            val hub = FakeVehicleLocationHub()
            val buffer = FakeVehicleLocationBuffer()
            val proximityAlertService = FakeProximityAlertService()
            // No entry for vehicle 7 — findById returns null, so there is no companyId to check.
            val service =
                newService(
                    activeAssignment = activeAssignment(vehicleId = 7),
                    hub = hub,
                    buffer = buffer,
                    vehicleRepository = FakeVehicleRepository(companyIdByVehicleId = emptyMap()),
                    proximityAlertService = proximityAlertService,
                )
            val (lat, lon) = budapest

            service
                .ingestTelemetry(vendorUserId = 1, points = listOf(point(lat, lon, Instant.now())))
                .fold(onSuccess = {}, onError = { fail("expected success but got $it") })

            assertTrue(proximityAlertService.evaluations.isEmpty())
        }
    }

    private fun activeAssignment(vehicleId: Int) =
        VehicleAssignment(id = 1, vehicleId = vehicleId, vendorUserId = 1, startedAt = Instant.now())

    private fun newService(
        activeAssignment: VehicleAssignment?,
        hub: VehicleLocationHub,
        buffer: VehicleLocationBuffer,
        vehicleRepository: VehicleRepository = FakeVehicleRepository(companyIdByVehicleId = emptyMap()),
        proximityAlertService: ProximityAlertService = FakeProximityAlertService(),
    ) = VehicleTrackingServiceI(
        vehicleAssignmentService = FakeVehicleAssignmentService(activeAssignment),
        vehicleRepository = vehicleRepository,
        cellIndexer = H3CellIndexer(),
        hub = hub,
        buffer = buffer,
        proximityAlertService = proximityAlertService,
    )
}

private class FakeVehicleRepository(
    private val companyIdByVehicleId: Map<Int, Int>,
) : VehicleRepository {
    override suspend fun create(
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String?,
    ) = error("not exercised by this test")

    override suspend fun findById(vehicleId: Int): Vehicle? =
        companyIdByVehicleId[vehicleId]?.let { companyId ->
            Vehicle(
                id = vehicleId,
                companyId = companyId,
                label = "v$vehicleId",
                licensePlate = "PLATE-$vehicleId",
                pictureUrl = null,
                createdAt = Instant.now(),
            )
        }

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

private class FakeProximityAlertService : ProximityAlertService {
    data class Evaluation(
        val vehicleId: Int,
        val companyId: Int,
        val latitude: Double,
        val longitude: Double,
    )

    val evaluations = mutableListOf<Evaluation>()

    // Distinct from evaluations.size: proves the *batch call itself* happened once per
    // ingestTelemetry invocation, regardless of how many points ended up in that one call.
    var evaluateBatchCallCount = 0
        private set

    override suspend fun evaluateBatch(
        vehicleId: Int,
        companyId: Int,
        points: List<GeoPoint>,
    ) {
        evaluateBatchCallCount++
        points.forEach { point -> evaluations += Evaluation(vehicleId, companyId, point.latitude, point.longitude) }
    }
}

private class FakeVehicleAssignmentService(
    private val activeAssignment: VehicleAssignment?,
) : VehicleAssignmentService {
    override suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<VehicleAssignment, VehicleAssignmentError> = error("not exercised by this test")

    override suspend fun unlink(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> = error("not exercised by this test")

    override suspend fun endActiveAssignment(
        adminUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> = error("not exercised by this test")

    override suspend fun endActiveAssignmentForVendorInCompany(
        vendorUserId: Int,
        companyId: Int,
    ) = error("not exercised by this test")

    override suspend fun endAllActiveAssignmentsForCompany(companyId: Int) = error("not exercised by this test")

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? = activeAssignment

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? = error("not exercised by this test")
}

private class FakeVehicleLocationHub : VehicleLocationHub {
    val published = mutableListOf<VehicleLocationUpdate>()
    private val latestByVehicleId = mutableMapOf<Int, VehicleLocationUpdate>()

    override fun publish(update: VehicleLocationUpdate) {
        published += update
        latestByVehicleId[update.vehicleId] = update
    }

    override fun markOffline(vehicleId: Int) = error("not exercised by this test")

    override fun lastKnownLocation(vehicleId: Int): VehicleLocationUpdate? = latestByVehicleId[vehicleId]

    override fun subscribeNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<VehicleLiveEvent> = error("not exercised by this test")

    override fun subscribeAll(): Flow<VehicleLiveEvent> = error("not exercised by this test")

    override fun snapshotNear(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): List<VehicleLocationUpdate> = error("not exercised by this test")

    override fun snapshotAll(): List<VehicleLocationUpdate> = error("not exercised by this test")
}

private class FakeVehicleLocationBuffer : VehicleLocationBuffer {
    val enqueued = mutableListOf<VehicleLocationUpdate>()

    override fun enqueue(update: VehicleLocationUpdate) {
        enqueued += update
    }

    override fun drainAll(): List<VehicleLocationUpdate> = enqueued.toList()
}
