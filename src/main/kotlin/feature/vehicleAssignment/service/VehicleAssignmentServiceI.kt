package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.MembershipCheck
import com.mozgobolt.feature.company.domain.requireAdminMembership
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import java.time.Instant

/**
 * Thrown only within [VehicleAssignmentServiceI.link]'s own `tx.transactional { }` block, and
 * caught immediately around that call — never crosses `link`'s own boundary. Forces
 * [com.mozgobolt.core.database.TransactionalRunner.transactional] to roll back the whole
 * transaction (Exposed's `suspendTransaction` only rolls back on a thrown exception, not on a
 * returned [AppResult.Error]) when a concurrent `link()` for the same vehicle wins the race: this
 * undoes this call's own `endAssignment` of the vendor's *previous* active assignment, which would
 * otherwise silently commit even though the call is reporting failure — leaving the vendor with no
 * active assignment at all.
 */
private class LostAssignmentRaceException : RuntimeException()

class VehicleAssignmentServiceI(
    private val vehicleAssignmentRepository: VehicleAssignmentRepository,
    private val vehicleRepository: VehicleRepository,
    private val membershipRepository: CompanyMembershipRepository,
    private val syncService: SyncService,
    private val hub: VehicleLocationHub,
    private val tx: TransactionalRunner,
) : VehicleAssignmentService {
    @Suppress("ReturnCount")
    override suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<VehicleAssignment, VehicleAssignmentError> =
        try {
            tx.transactional {
                val vehicle =
                    vehicleRepository.findById(vehicleId)
                        ?: return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND)

                if (vehicle.archivedAt != null) {
                    return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_ARCHIVED)
                }

                val isMember = membershipRepository.find(vehicle.companyId, vendorUserId) != null
                if (!isMember) return@transactional AppResult.Error(VehicleAssignmentError.FORBIDDEN)

                val activeOnVehicle = vehicleAssignmentRepository.findActiveForVehicle(vehicleId)
                if (activeOnVehicle != null && activeOnVehicle.vendorUserId != vendorUserId) {
                    return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED)
                }
                if (activeOnVehicle != null) return@transactional AppResult.Success(activeOnVehicle)

                vehicleAssignmentRepository.findActiveForVendor(vendorUserId)?.let {
                    vehicleAssignmentRepository.endAssignment(it.id, Instant.now())
                }

                // A concurrent link() for this same vehicle (or vendor) between the checks above and
                // this insert loses here, at the DB's partial unique indexes, not before — the checks
                // above are for the common case's clean error message; this is the actual guard.
                val created =
                    vehicleAssignmentRepository.startAssignment(vehicleId, vendorUserId, Instant.now())
                        ?: run {
                            // Lost the race — but if the winner turns out to be this same vendor (a
                            // double-submitted request racing itself, the one case the checks above
                            // couldn't have caught either), that's still a success, not an error.
                            // Nothing new was created by *this* call, so no sync event for it either
                            // — the winning call already recorded its own.
                            val winner = vehicleAssignmentRepository.findActiveForVehicle(vehicleId)
                            if (winner != null && winner.vendorUserId == vendorUserId) {
                                return@transactional AppResult.Success(winner)
                            }
                            // Someone else won: throw (see LostAssignmentRaceException's kdoc) so
                            // this transaction rolls back instead of committing the endAssignment
                            // above — otherwise the vendor's previous session would be permanently
                            // lost even though this call reports failure.
                            throw LostAssignmentRaceException()
                        }
                syncService.recordChange(
                    vendorUserId,
                    SyncEntityType.VEHICLE_ASSIGNMENT,
                    created.id,
                    SyncOperation.UPSERT,
                )

                AppResult.Success(created)
            }
        } catch (_: LostAssignmentRaceException) {
            AppResult.Error(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED)
        }

    override suspend fun unlink(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> =
        tx.transactional {
            val active = vehicleAssignmentRepository.findActiveForVendor(vendorUserId)
            if (active == null || active.vehicleId != vehicleId) {
                return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED)
            }

            endAssignmentAndNotify(active)

            AppResult.Success(Unit)
        }

    @Suppress("ReturnCount")
    override suspend fun endActiveAssignment(
        adminUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> =
        tx.transactional {
            val vehicle =
                vehicleRepository.findById(vehicleId)
                    ?: return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND)

            // Both "not a member at all" and "a member but not an admin" collapse to the same
            // NOT_ADMIN error here — this feature has never distinguished the two, unlike company/
            // vehicle management, which do.
            when (membershipRepository.requireAdminMembership(vehicle.companyId, adminUserId)) {
                MembershipCheck.NotAMember, MembershipCheck.NotAdmin ->
                    return@transactional AppResult.Error(VehicleAssignmentError.NOT_ADMIN)

                is MembershipCheck.Admin -> Unit
            }

            val active =
                vehicleAssignmentRepository.findActiveForVehicle(vehicleId)
                    ?: return@transactional AppResult.Error(VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT)

            endAssignmentAndNotify(active)

            AppResult.Success(Unit)
        }

    override suspend fun endActiveAssignmentForVendorInCompany(
        vendorUserId: Int,
        companyId: Int,
    ) {
        tx.transactional {
            // A vendor can only ever have ONE active assignment at a time, globally (the DB's
            // partial unique index on vendor_user_id) — so there's at most one to check, and it
            // only matters here if it happens to be for a vehicle owned by *this* company.
            val active = vehicleAssignmentRepository.findActiveForVendor(vendorUserId) ?: return@transactional
            val vehicle = vehicleRepository.findById(active.vehicleId)
            if (vehicle?.companyId != companyId) return@transactional

            endAssignmentAndNotify(active)
        }
    }

    override suspend fun endAllActiveAssignmentsForCompany(companyId: Int) {
        tx.transactional {
            vehicleRepository.findAllByCompany(companyId).forEach { vehicle ->
                vehicleAssignmentRepository.findActiveForVehicle(vehicle.id)?.let { endAssignmentAndNotify(it) }
            }
        }
    }

    /**
     * Ends [assignment] and performs the two things that must always accompany that: tells the
     * vendor's own devices via sync (attributed to them, not whoever triggered this — a
     * self-unlink, an admin's forced end, and an end triggered by company-membership removal all
     * end up here), and clears the vehicle from the live map. Every path that ends an active
     * assignment must go through this, not touch the repository directly.
     */
    private suspend fun endAssignmentAndNotify(assignment: VehicleAssignment) {
        vehicleAssignmentRepository.endAssignment(assignment.id, Instant.now())
        syncService.recordChange(
            assignment.vendorUserId,
            SyncEntityType.VEHICLE_ASSIGNMENT,
            assignment.id,
            SyncOperation.UPSERT,
        )
        hub.markOffline(assignment.vehicleId)
    }

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
        tx.transactional { vehicleAssignmentRepository.findActiveForVendor(vendorUserId) }

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
        tx.transactional { vehicleAssignmentRepository.findActiveForVehicle(vehicleId) }
}
