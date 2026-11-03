/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.withRealVehiclePingDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.toJavaDuration

/**
 * Exercises the database-backed cooldown state and the vehicle-row lock that serializes concurrent
 * pings. These PostgreSQL behaviors cannot be established by the service fakes alone.
 */
class VehiclePingServiceRealDatabaseTest {
    private class VehicleRowLockGate {
        val firstLockAcquired = CompletableDeferred<Unit>()
        val secondLockRequested = CompletableDeferred<Unit>()
        val secondLockAcquired = CompletableDeferred<Unit>()
        val releaseFirstLock = CompletableDeferred<Unit>()
        private val findForUpdateCalls = AtomicInteger()

        fun wrap(delegate: VehicleRepository): VehicleRepository =
            object : VehicleRepository by delegate {
                override suspend fun findByIdForUpdate(vehicleId: Int): Vehicle? {
                    when (findForUpdateCalls.incrementAndGet()) {
                        1 -> {
                            val vehicle = delegate.findByIdForUpdate(vehicleId)
                            firstLockAcquired.complete(Unit)
                            withTimeout(5_000) { releaseFirstLock.await() }
                            return vehicle
                        }

                        2 -> {
                            secondLockRequested.complete(Unit)
                            val vehicle = delegate.findByIdForUpdate(vehicleId)
                            secondLockAcquired.complete(Unit)
                            return vehicle
                        }

                        else -> {
                            return delegate.findByIdForUpdate(vehicleId)
                        }
                    }
                }
            }
    }

    @Test
    fun `a second ping from the same buyer to the same vehicle is rejected by the real cooldown query`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()

            val first =
                harness.ping(buyerUserId = 1, vehicleId = vehicleId, latitude = 47.4979, longitude = 19.0402)
            val second =
                harness.ping(buyerUserId = 1, vehicleId = vehicleId, latitude = 47.4979, longitude = 19.0402)

            first.fold(onSuccess = {}, onError = { error("setup failed: $it") })
            second.fold(onSuccess = { error("expected COOLDOWN_ACTIVE but got success") }, onError = {
                assertEquals(PingError.COOLDOWN_ACTIVE, it)
            })
        }
    }

    @Test
    fun `a different buyer's ping is unaffected by another buyer's cooldown, against a real database`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()
            harness
                .ping(
                    buyerUserId = 1,
                    vehicleId = vehicleId,
                    latitude = 47.4979,
                    longitude = 19.0402,
                ).fold(onSuccess = {}, onError = { error("$it") })

            val result =
                harness.service.ping(buyerUserId = 2, vehicleId = vehicleId, latitude = 47.4979, longitude = 19.0402)

            result.fold(onSuccess = {}, onError = { error("expected success but got $it") })
        }
    }

    @Test
    fun `the exact cooldown boundary is allowed using persisted PostgreSQL timestamps`() {
        skipIfNoDocker()

        val now = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS)
        withRealVehiclePingDatabase(clock = Clock.fixed(now, ZoneOffset.UTC)) { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()
            harness.tx.transactional {
                harness.pingRepository.recordLatest(
                    vehicleId = vehicleId,
                    buyerUserId = 1,
                    sentAt = now.minus(PING_COOLDOWN.toJavaDuration()),
                )
            }

            val result = harness.ping(1, vehicleId, 47.4979, 19.0402)

            result.fold(
                onSuccess = { assertEquals(now, it.sentAt) },
                onError = { error("the exact five-minute cooldown boundary should be allowed: $it") },
            )
        }
    }

    @Test
    fun `pinging a vehicle with no active assignment is rejected against a real database`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId =
                harness.tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Idle Co",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    VehicleRepositoryI()
                        .create(
                            companyId = company.id,
                            label = "Idle Truck",
                            licensePlate = "IDLE-1",
                        )!!
                        .id
                }

            val result =
                harness.ping(buyerUserId = 1, vehicleId = vehicleId, latitude = 47.4979, longitude = 19.0402)

            result.fold(onSuccess = { error("expected VEHICLE_NOT_ACTIVE but got success") }, onError = {
                assertEquals(PingError.VEHICLE_NOT_ACTIVE, it)
            })
        }
    }

    @Test
    fun `pinging a vehicle that doesn't exist is rejected against a real database`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val result =
                harness.ping(buyerUserId = 1, vehicleId = 999_999, latitude = 47.4979, longitude = 19.0402)

            result.fold(onSuccess = { error("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(PingError.VEHICLE_NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `concurrent pings from one buyer to one vehicle serialize at the vehicle row`() {
        skipIfNoDocker()

        val lockGate = VehicleRowLockGate()
        withRealVehiclePingDatabase(vehicleRepositoryDecorator = lockGate::wrap) { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()

            val outcomes =
                coroutineScope {
                    val first =
                        async(Dispatchers.IO) {
                            harness.ping(1, vehicleId, 47.4979, 19.0402)
                        }
                    withTimeout(5_000) { lockGate.firstLockAcquired.await() }

                    val second =
                        async(Dispatchers.IO) {
                            harness.ping(1, vehicleId, 47.4979, 19.0402)
                        }
                    try {
                        withTimeout(5_000) { lockGate.secondLockRequested.await() }
                        assertEquals(
                            null,
                            withTimeoutOrNull(250) { lockGate.secondLockAcquired.await() },
                            "the second transaction must wait while the first holds the vehicle row lock",
                        )
                    } finally {
                        lockGate.releaseFirstLock.complete(Unit)
                    }

                    listOf(first.await(), second.await())
                }

            assertEquals(
                1,
                outcomes.count { outcome -> outcome.fold(onSuccess = { true }, onError = { false }) },
            )
            assertEquals(
                1,
                outcomes.count { outcome ->
                    outcome.fold(onSuccess = { false }, onError = { it == PingError.COOLDOWN_ACTIVE })
                },
            )
            assertEquals(
                1L,
                harness.tx.transactional {
                    VehiclePingsTable
                        .selectAll()
                        .where {
                            (VehiclePingsTable.vehicleId eq vehicleId) and
                                (VehiclePingsTable.buyerUserId eq 1)
                        }.count()
                },
                "only the winning request may persist a ping",
            )
        }
    }
}
