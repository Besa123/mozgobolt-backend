package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleServiceITest {
    @Test
    fun `an admin can create a vehicle for their company`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)

            val result =
                fx.service.createVehicle(adminUserId = 10, companyId = 1, label = "Truck 1", licensePlate = "ABC-123")

            val vehicle = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals(1, vehicle.companyId)
            assertEquals("Truck 1", vehicle.label)
            assertEquals("ABC-123", vehicle.licensePlate)
            assertEquals(null, vehicle.pictureUrl)
            assertTrue(
                fx.syncService.recorded.any { it.entityType == SyncEntityType.VEHICLE && it.entityId == vehicle.id },
            )
        }
    }

    @Test
    fun `the vehicle label is trimmed before being stored`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)

            val result =
                fx.service.createVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    label = "  Truck 1  ",
                    licensePlate = "  ABC-123  ",
                )

            result.fold(
                onSuccess = {
                    assertEquals("Truck 1", it.label)
                    assertEquals("ABC-123", it.licensePlate)
                },
                onError = { fail("$it") },
            )
        }
    }

    @Test
    fun `a plain member cannot create a vehicle`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.MEMBER)

            val result =
                fx.service.createVehicle(adminUserId = 10, companyId = 1, label = "Truck 1", licensePlate = "ABC-123")

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(VehicleError.NOT_ADMIN, it)
            })
            assertTrue(fx.vehicleRepository.findAllByCompany(1).isEmpty())
        }
    }

    @Test
    fun `creating a vehicle is rejected for a caller who isn't a member of the company at all`() {
        runBlocking {
            val fx = VehicleServiceFixture()

            val result =
                fx.service.createVehicle(adminUserId = 10, companyId = 1, label = "Truck 1", licensePlate = "ABC-123")

            result.fold(onSuccess = { fail("expected NOT_A_MEMBER but got success") }, onError = {
                assertEquals(VehicleError.NOT_A_MEMBER, it)
            })
            assertTrue(fx.vehicleRepository.findAllByCompany(1).isEmpty())
        }
    }

    @Test
    fun `listing vehicles is rejected for a caller who isn't a member of the company`() {
        runBlocking {
            val fx = VehicleServiceFixture()

            val result = fx.service.listCompanyVehicles(userId = 10, companyId = 1)

            result.fold(onSuccess = { fail("expected NOT_A_MEMBER but got success") }, onError = {
                assertEquals(VehicleError.NOT_A_MEMBER, it)
            })
        }
    }

    @Test
    fun `a plain member, not just an admin, can list the company's vehicles`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.MEMBER)

            val result = fx.service.listCompanyVehicles(userId = 10, companyId = 1)

            result.fold(
                onSuccess = { assertEquals(emptyList(), it) },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `listing vehicles never leaks another company's fleet`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            fx.membershipRepository.seed(companyId = 2, userId = 20, role = CompanyRole.ADMIN)
            fx.service.createVehicle(10, 1, "A's truck", "PLATE-A").fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.createVehicle(20, 2, "B's truck", "PLATE-B").fold(onSuccess = {}, onError = { fail("$it") })

            val companyAFleet =
                fx.service
                    .listCompanyVehicles(
                        userId = 10,
                        companyId = 1,
                    ).fold(onSuccess = { it }, onError = { fail("$it") })

            assertEquals(listOf("A's truck"), companyAFleet.map { it.label })
        }
    }

    @Test
    fun `two vehicles can share the same label within a company`() {
        runBlocking {
            // No uniqueness invariant on label — deliberately not enforced, so this documents
            // the current, intentional behavior rather than leaving it assumed.
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)

            fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.createVehicle(10, 1, "Truck", "PLATE-2").fold(onSuccess = {}, onError = { fail("$it") })

            val fleet = fx.service.listCompanyVehicles(10, 1).fold(onSuccess = { it }, onError = { fail("$it") })
            assertEquals(2, fleet.size)
        }
    }

    @Test
    fun `creating a second vehicle with a license plate already used in the same company is rejected`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            fx.service.createVehicle(10, 1, "Truck 1", "SAME-PLATE").fold(onSuccess = {}, onError = { fail("$it") })

            val result =
                fx.service.createVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    label = "Truck 2",
                    licensePlate = "SAME-PLATE",
                )

            result.fold(onSuccess = { fail("expected LICENSE_PLATE_TAKEN but got success") }, onError = {
                assertEquals(VehicleError.LICENSE_PLATE_TAKEN, it)
            })
            assertEquals(1, fx.vehicleRepository.findAllByCompany(1).size)
            assertEquals(
                1,
                fx.syncService.recorded.size,
                "no sync event for a vehicle that was never actually created",
            )
        }
    }

    @Test
    fun `the same license plate is allowed across two different companies`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            fx.membershipRepository.seed(companyId = 2, userId = 20, role = CompanyRole.ADMIN)

            fx.service.createVehicle(10, 1, "A's truck", "SHARED-PLATE").fold(onSuccess = {}, onError = { fail("$it") })
            val result = fx.service.createVehicle(20, 2, "B's truck", "SHARED-PLATE")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    // --- updateVehicle() ---

    @Test
    fun `an admin can update a vehicle's plate and picture`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service
                    .createVehicle(
                        10,
                        1,
                        "Truck",
                        "OLD-PLATE",
                    ).fold(onSuccess = { it }, onError = { fail("$it") })

            val result =
                fx.service.updateVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = vehicle.id,
                    licensePlate = "NEW-PLATE",
                    pictureUrl = "https://example.com/truck.png",
                )

            val updated = result.fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            assertEquals("NEW-PLATE", updated.licensePlate)
            assertEquals("https://example.com/truck.png", updated.pictureUrl)
            assertTrue(
                fx.syncService.recorded.any { it.entityType == SyncEntityType.VEHICLE && it.entityId == vehicle.id },
            )
        }
    }

    @Test
    fun `a plain member cannot update a vehicle`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.MEMBER)
            val seeded = fx.vehicleRepository.create(1, "Truck", "OLD-PLATE", null)!!

            val result =
                fx.service.updateVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = seeded.id,
                    licensePlate = "NEW-PLATE",
                    pictureUrl = null,
                )

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(VehicleError.NOT_ADMIN, it)
            })
        }
    }

    @Test
    fun `updating a vehicle that doesn't exist is rejected`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)

            val result =
                fx.service.updateVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = 999,
                    licensePlate = "NEW-PLATE",
                    pictureUrl = null,
                )

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleError.VEHICLE_NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `updating a vehicle that belongs to a different company is rejected as not found`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            fx.membershipRepository.seed(companyId = 2, userId = 20, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service
                    .createVehicle(20, 2, "B's truck", "PLATE-B")
                    .fold(onSuccess = { it }, onError = { fail("$it") })

            val result =
                fx.service.updateVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = vehicle.id,
                    licensePlate = "NEW-PLATE",
                    pictureUrl = null,
                )

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleError.VEHICLE_NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `updating a vehicle to a plate already used by a different vehicle in the same company is rejected`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            fx.service.createVehicle(10, 1, "Truck 1", "TAKEN-PLATE").fold(onSuccess = {}, onError = { fail("$it") })
            val vehicle2 =
                fx.service
                    .createVehicle(10, 1, "Truck 2", "OWN-PLATE")
                    .fold(onSuccess = { it }, onError = { fail("$it") })

            val result =
                fx.service.updateVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = vehicle2.id,
                    licensePlate = "TAKEN-PLATE",
                    pictureUrl = null,
                )

            result.fold(onSuccess = { fail("expected LICENSE_PLATE_TAKEN but got success") }, onError = {
                assertEquals(VehicleError.LICENSE_PLATE_TAKEN, it)
            })
        }
    }

    @Test
    fun `updating a vehicle to keep its own current plate is allowed`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service
                    .createVehicle(10, 1, "Truck", "SAME-PLATE")
                    .fold(onSuccess = { it }, onError = { fail("$it") })

            val result =
                fx.service.updateVehicle(
                    adminUserId = 10,
                    companyId = 1,
                    vehicleId = vehicle.id,
                    licensePlate = "SAME-PLATE",
                    pictureUrl = "https://example.com/new.png",
                )

            result.fold(
                onSuccess = { assertEquals("https://example.com/new.png", it.pictureUrl) },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    // --- findVehicle() ---

    @Test
    fun `findVehicle returns a vehicle regardless of the caller's company membership`() {
        runBlocking {
            // No membership seeded at all — findVehicle is deliberately not membership-gated,
            // unlike listCompanyVehicles, since any authenticated buyer needs to look up a
            // vehicle they see on the public live map.
            val fx = VehicleServiceFixture()
            val seeded = fx.vehicleRepository.create(1, "Truck", "PLATE-1", "https://example.com/truck.png")!!

            val result = fx.service.findVehicle(seeded.id)

            result.fold(
                onSuccess = {
                    assertEquals(seeded.id, it.id)
                    assertEquals("PLATE-1", it.licensePlate)
                    assertEquals("https://example.com/truck.png", it.pictureUrl)
                },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `findVehicle for an unknown id is rejected as not found`() {
        runBlocking {
            val fx = VehicleServiceFixture()

            val result = fx.service.findVehicle(999)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleError.VEHICLE_NOT_FOUND, it)
            })
        }
    }
}
