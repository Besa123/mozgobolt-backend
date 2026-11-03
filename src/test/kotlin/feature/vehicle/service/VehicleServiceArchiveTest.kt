/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.service

import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class VehicleServiceArchiveTest {
    @Test
    fun `an admin can archive a vehicle with no active session`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertTrue(fx.vehicleRepository.findById(vehicle.id)!!.archivedAt != null)
            assertTrue(fx.vehicleRepository.findAllByCompany(1).isEmpty(), "archived vehicles must not be listed")
            assertEquals(0, fx.vehicleAssignmentService.endActiveAssignmentForVendorInCompanyCallCount)
            assertTrue(
                fx.syncService.recorded.any { it.entityType == SyncEntityType.VEHICLE && it.entityId == vehicle.id },
            )
            assertTrue(fx.syncService.recorded.none { it.entityType == SyncEntityType.VEHICLE_ASSIGNMENT })
        }
    }

    @Test
    fun `archiving a vehicle with an active session batches assignment and vehicle sync changes by user`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.vehicleAssignmentService.seedActiveAssignment(
                companyId = 1,
                vehicleId = vehicle.id,
                vendorUserId = 20,
            )

            val result = fx.service.archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(1, fx.vehicleAssignmentService.endActiveAssignmentForVendorInCompanyCallCount)
            val archiveChanges = fx.syncService.recorded.takeLast(2)
            assertEquals(listOf(10, 20), archiveChanges.map { it.userId })
            assertEquals(
                listOf(SyncEntityType.VEHICLE, SyncEntityType.VEHICLE_ASSIGNMENT),
                archiveChanges.map { it.entityType },
            )
            assertEquals(listOf(SyncOperation.UPSERT, SyncOperation.UPSERT), archiveChanges.map { it.operation })
        }
    }

    @Test
    fun `archive aborts if an active assignment could not be ended`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.vehicleAssignmentService.seedActiveAssignment(
                companyId = 1,
                vehicleId = vehicle.id,
                vendorUserId = 20,
            )
            fx.vehicleAssignmentService.failEndingActiveAssignment()

            val result = fx.service.archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleError.VEHICLE_NOT_FOUND, it)
            })
            assertEquals(
                2,
                fx.vehicleAssignmentService.endActiveAssignmentForVendorInCompanyCallCount,
                "archive rechecks and retries only while the assignment is still active",
            )
            assertEquals(null, fx.vehicleRepository.findById(vehicle.id)?.archivedAt)
            assertEquals(1, fx.syncService.recorded.size, "only vehicle creation should have been synced")
        }
    }

    @Test
    fun `archiving an already-archived vehicle is a clean no-op success`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service
                .archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)
                .fold(onSuccess = {}, onError = { fail("$it") })
            assertEquals(0, fx.vehicleAssignmentService.endActiveAssignmentForVendorInCompanyCallCount)

            val result = fx.service.archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(
                0,
                fx.vehicleAssignmentService.endActiveAssignmentForVendorInCompanyCallCount,
                "re-archiving must not attempt to end an assignment a second time",
            )
        }
    }

    @Test
    fun `a plain member cannot archive a vehicle`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.MEMBER)
            val seeded = fx.vehicleRepository.create(1, "Truck", "PLATE-1", null)!!

            val result = fx.service.archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = seeded.id)

            result.fold(onSuccess = { fail("expected NOT_ADMIN but got success") }, onError = {
                assertEquals(VehicleError.NOT_ADMIN, it)
            })
        }
    }

    @Test
    fun `archiving a vehicle that belongs to a different company is rejected as not found`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            fx.membershipRepository.seed(companyId = 2, userId = 20, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service
                    .createVehicle(20, 2, "B's truck", "PLATE-B")
                    .fold(onSuccess = { it }, onError = { fail("$it") })

            val result = fx.service.archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleError.VEHICLE_NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `findVehicle treats an archived vehicle as not found`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service
                .archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)
                .fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.findVehicle(vehicle.id)

            result.fold(onSuccess = { fail("expected VEHICLE_NOT_FOUND but got success") }, onError = {
                assertEquals(VehicleError.VEHICLE_NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `updateVehicle rejects an archived vehicle as not found`() {
        runBlocking {
            val fx = VehicleServiceFixture()
            fx.membershipRepository.seed(companyId = 1, userId = 10, role = CompanyRole.ADMIN)
            val vehicle =
                fx.service.createVehicle(10, 1, "Truck", "PLATE-1").fold(onSuccess = { it }, onError = { fail("$it") })
            fx.service
                .archiveVehicle(adminUserId = 10, companyId = 1, vehicleId = vehicle.id)
                .fold(onSuccess = {}, onError = { fail("$it") })

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
}
