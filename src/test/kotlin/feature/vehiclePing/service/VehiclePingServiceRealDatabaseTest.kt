package com.mozgobolt.feature.vehiclePing.service

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.withRealVehiclePingDatabase
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [VehiclePingServiceITest] proves the *service* correctly reacts to what
 * [com.mozgobolt.feature.vehiclePing.domain.VehiclePingRepository.mostRecentSentAt] returns; this
 * proves the underlying query itself — `ORDER BY sent_at DESC LIMIT 1` scoped to
 * `(vehicle_id, buyer_user_id)` — actually returns the right row against a real Postgres, backed
 * by the index this feature's migration adds, rather than trusting that by reasoning alone.
 */
class VehiclePingServiceRealDatabaseTest {
    @Test
    fun `a second ping from the same buyer to the same vehicle is rejected by the real cooldown query`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()

            val first =
                harness.service.ping(buyerUserId = 1, vehicleId = vehicleId, latitude = 47.4979, longitude = 19.0402)
            val second =
                harness.service.ping(buyerUserId = 1, vehicleId = vehicleId, latitude = 47.4979, longitude = 19.0402)

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
            harness.service
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
    fun `pinging a vehicle with no active assignment is rejected against a real database`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId =
                harness.tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "Idle Co",
                            inviteCode = "idle-${Instant.now().toEpochMilli()}",
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
                harness.service.ping(buyerUserId = 1, vehicleId = vehicleId, latitude = 47.4979, longitude = 19.0402)

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
                harness.service.ping(buyerUserId = 1, vehicleId = 999_999, latitude = 47.4979, longitude = 19.0402)

            result.fold(onSuccess = { error("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(PingError.VEHICLE_NOT_FOUND, it)
            })
        }
    }
}
