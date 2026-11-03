/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.domain

import com.mozgobolt.feature.vehicle.domain.model.Vehicle

/** Persistence operations for vehicle records and their archival state. */
interface VehicleRepository {
    /**
     * `null` means a concurrent request already took this exact (companyId, licensePlate) pair
     * first — the DB's `UNIQUE(company_id, license_plate)` index is the actual guard, mirroring
     * [com.mozgobolt.feature.company.domain.CompanyMembershipRepository.addIfAbsent]'s contract.
     * The caller turns that into [com.mozgobolt.feature.vehicle.domain.model.VehicleError.LICENSE_PLATE_TAKEN]
     * rather than letting a raw constraint violation surface.
     */
    suspend fun create(
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String? = null,
    ): Vehicle?

    /** Finds a vehicle by its identifier. */
    suspend fun findById(vehicleId: Int): Vehicle?

    /**
     * Reads a fresh vehicle snapshot while locking its row for the caller's transaction.
     * Use after locking the owning company row when a vehicle state change must serialize with
     * company membership, assignment, or deletion operations.
     */
    suspend fun findByIdForUpdate(vehicleId: Int): Vehicle?

    /** Lists the vehicles associated with a company. */
    suspend fun findAllByCompany(companyId: Int): List<Vehicle>

    /** Finds a vehicle by its company and license plate. */
    suspend fun findByCompanyAndPlate(
        companyId: Int,
        licensePlate: String,
    ): Vehicle?

    /** `null` if [vehicleId] doesn't exist. Does not check plate uniqueness — the caller is
     * expected to have already checked via [findByCompanyAndPlate] inside the same transaction. */
    suspend fun updateDetails(
        vehicleId: Int,
        licensePlate: String,
        pictureUrl: String?,
        pictureStorageKey: String? = null,
    ): Vehicle?

    /**
     * Sets `archivedAt` to now, but only if it isn't already set — a conditional `UPDATE`, so
     * re-archiving an already-archived vehicle is a safe no-op that never overwrites the original
     * archive instant. Returns `false` if [vehicleId] doesn't exist or was already archived; the
     * caller (which already loaded the vehicle to check this) treats either as "nothing left to
     * do," not an error.
     */
    suspend fun archive(vehicleId: Int): Boolean

    /** `null` if [vehicleId] doesn't exist — mirrors [updateDetails]'s contract, scoped to just the picture. */
    suspend fun updatePictureUrl(
        vehicleId: Int,
        pictureUrl: String?,
        pictureStorageKey: String? = null,
    ): Vehicle?
}
