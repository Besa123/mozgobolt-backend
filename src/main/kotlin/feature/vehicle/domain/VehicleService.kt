/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.domain.model.VehicleError

/** Vehicle-management use cases with company authorization enforced by the service layer. */
interface VehicleService {
    /** Creates a vehicle for a company managed by the caller. */
    suspend fun createVehicle(
        adminUserId: Int,
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String? = null,
    ): AppResult<Vehicle, VehicleError>

    /** Updates a vehicle's details and picture URL. */
    suspend fun updateVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
        licensePlate: String,
        pictureUrl: String?,
        clearPictureUrl: Boolean = false,
    ): AppResult<Vehicle, VehicleError>

    /** Lists the vehicles belonging to a company visible to the caller. */
    suspend fun listCompanyVehicles(
        userId: Int,
        companyId: Int,
    ): AppResult<List<Vehicle>, VehicleError>

    /**
     * Unlike [listCompanyVehicles], deliberately not membership-gated — any authenticated user
     * (buyer or vendor) can look up a vehicle they see on the public live map, not just this
     * company's own members. An archived vehicle is treated as not found, same as one belonging
     * to a different company.
     */
    suspend fun findVehicle(vehicleId: Int): AppResult<Vehicle, VehicleError>

    /**
     * Soft-deletes (archives) a vehicle rather than removing its row — its driving history
     * survives, governed by the existing 30-day `vehicle_locations` retention window, not by this
     * action. Force-ends whatever active assignment the vehicle has, if any, through the proper
     * path (so the live map is cleared too). Archiving an already-archived vehicle is a clean
     * no-op success, not an error.
     */
    suspend fun archiveVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleError>

    /**
     * Scans, sanitizes (re-encodes to a bounded-size JPEG), and stores [rawBytes] under a
     * versioned private key. The active key is committed with [Vehicle.pictureUrl] so failed or
     * concurrent uploads cannot overwrite the currently served image. Old objects are deleted
     * best-effort after commit.
     */
    suspend fun uploadVehiclePicture(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
        rawBytes: ByteArray,
    ): AppResult<Vehicle, VehicleError>

    /** The raw bytes behind [Vehicle.pictureUrl] when it points at this backend's own serving route. */
    suspend fun getVehiclePicture(vehicleId: Int): AppResult<ByteArray, VehicleError>
}
