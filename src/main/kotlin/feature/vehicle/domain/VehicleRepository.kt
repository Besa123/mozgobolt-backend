package com.mozgobolt.feature.vehicle.domain

import com.mozgobolt.feature.vehicle.domain.model.Vehicle

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

    suspend fun findById(vehicleId: Int): Vehicle?

    suspend fun findAllByCompany(companyId: Int): List<Vehicle>

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
    ): Vehicle?
}
