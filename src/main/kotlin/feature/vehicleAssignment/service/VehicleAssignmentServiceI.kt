/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.MembershipCheck
import com.mozgobolt.feature.company.domain.requireAdminMembership
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentSessionTerminator
import com.mozgobolt.feature.vehicleAssignment.domain.model.ActiveVehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import java.time.Instant

/**
 * Thrown only within [VehicleAssignmentServiceI.link]'s own `tx.transactional { }` block, and
 * caught immediately around that call — never crosses `link`'s own boundary. Forces
 * [com.mozgobolt.core.database.TransactionalRunner.transactional] to roll back the whole
 * transaction (Exposed's `suspendTransaction` only rolls back on a thrown exception, not on a
 * returned [AppResult.Error]) when concurrent links for the same vendor in different companies
 * race on the global partial unique index: this undoes this call's own `endAssignment` of the
 * vendor's *previous* active assignment, which would otherwise silently commit even though the
 * call is reporting failure — leaving the vendor with no active assignment at all.
 */
private class LostAssignmentRaceException : RuntimeException()

class VehicleAssignmentServiceI(
    private val vehicleAssignmentRepository: VehicleAssignmentRepository,
    private val companyRepository: CompanyRepository,
    private val vehicleRepository: VehicleRepository,
    private val membershipRepository: CompanyMembershipRepository,
    private val syncService: SyncService,
    private val hub: VehicleLocationHub,
    private val tx: TransactionalRunner,
) : VehicleAssignmentService,
    VehicleAssignmentSessionTerminator {
    @Suppress("ReturnCount")
    override suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<VehicleAssignment, VehicleAssignmentError> =
        link(vendorUserId, vehicleId, shareLocationWithCustomers = false)

    override suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
        shareLocationWithCustomers: Boolean,
    ): AppResult<VehicleAssignment, VehicleAssignmentError> {
        val outcome: Pair<AppResult<VehicleAssignment, VehicleAssignmentError>, VehicleAssignment?> =
            try {
                tx.transactional {
                    val vehicle =
                        findLinkableVehicle(vendorUserId, vehicleId).fold(
                            onSuccess = { it },
                            onError = { error -> return@transactional AppResult.Error(error) to null },
                        )

                    val activeOnVehicle = vehicleAssignmentRepository.findActiveForVehicle(vehicle.id)
                    if (activeOnVehicle != null && activeOnVehicle.vendorUserId != vendorUserId) {
                        return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED) to null
                    }
                    if (activeOnVehicle != null) {
                        return@transactional AppResult.Success(activeOnVehicle) to null
                    }

                    val previousAssignment = vehicleAssignmentRepository.findActiveForVendor(vendorUserId)
                    val endedPreviousAssignment =
                        previousAssignment?.takeIf {
                            vehicleAssignmentRepository.endAssignment(it.id, Instant.now())
                        }

                    // A concurrent link() for the same vendor in another company can still race here;
                    // the partial unique indexes are the final guard across independent company locks.
                    val now = Instant.now()
                    val created =
                        vehicleAssignmentRepository.startAssignment(
                            vehicleId = vehicle.id,
                            vendorUserId = vendorUserId,
                            startedAt = now,
                            locationSharingConsentedAt = now.takeIf { shareLocationWithCustomers },
                        )
                            ?: run {
                                // Lost the race — but if the winner turns out to be this same vendor (a
                                // double-submitted request racing itself, the one case the checks above
                                // couldn't have caught either), that's still a success, not an error.
                                // Nothing new was created by *this* call, so no sync event for it either
                                // — the winning call already recorded its own.
                                val winner = vehicleAssignmentRepository.findActiveForVehicle(vehicle.id)
                                if (winner != null && winner.vendorUserId == vendorUserId) {
                                    endedPreviousAssignment?.let {
                                        syncService.recordChange(
                                            userId = vendorUserId,
                                            entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                                            entityId = it.id,
                                            operation = SyncOperation.UPSERT,
                                        )
                                    }
                                    return@transactional AppResult.Success(winner) to endedPreviousAssignment
                                }
                                // Someone else won: throw (see LostAssignmentRaceException's kdoc) so
                                // this transaction rolls back instead of committing the endAssignment
                                // above — otherwise the vendor's previous session would be permanently
                                // lost even though this call reports failure.
                                throw LostAssignmentRaceException()
                            }
                    endedPreviousAssignment?.let {
                        syncService.recordChange(
                            userId = vendorUserId,
                            entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                            entityId = it.id,
                            operation = SyncOperation.UPSERT,
                        )
                    }
                    syncService.recordChange(
                        userId = vendorUserId,
                        entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                        entityId = created.id,
                        operation = SyncOperation.UPSERT,
                    )

                    AppResult.Success(created) to endedPreviousAssignment
                }
            } catch (_: LostAssignmentRaceException) {
                AppResult.Error(VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED) to null
            }

        val (result, endedPreviousAssignment) = outcome
        if (result is AppResult.Success) endedPreviousAssignment?.let { notifyEndedAssignments(listOf(it)) }
        return result
    }

    @Suppress("ReturnCount")
    private suspend fun findLinkableVehicle(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<Vehicle, VehicleAssignmentError> {
        val initialVehicle =
            vehicleRepository.findById(vehicleId)
                ?: return AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND)
        if (membershipRepository.find(initialVehicle.companyId, vendorUserId) == null) {
            return AppResult.Error(VehicleAssignmentError.FORBIDDEN)
        }
        if (companyRepository.findByIdForUpdate(initialVehicle.companyId) == null) {
            return AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND)
        }

        val vehicle =
            vehicleRepository.findByIdForUpdate(vehicleId)
                ?: return AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND)
        if (vehicle.companyId != initialVehicle.companyId) {
            return AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND)
        }
        if (vehicle.archivedAt != null) return AppResult.Error(VehicleAssignmentError.VEHICLE_ARCHIVED)
        if (membershipRepository.find(vehicle.companyId, vendorUserId) == null) {
            return AppResult.Error(VehicleAssignmentError.FORBIDDEN)
        }

        return AppResult.Success(vehicle)
    }

    override suspend fun unlink(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> {
        val (result, endedAssignment) =
            tx.transactional {
                val initial =
                    vehicleAssignmentRepository.findActiveForVendor(vendorUserId)
                        ?: return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED) to null
                if (initial.vehicleId != vehicleId) {
                    return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED) to null
                }

                val initialVehicle =
                    vehicleRepository.findById(initial.vehicleId)
                        ?: return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED) to null
                if (companyRepository.findByIdForUpdate(initialVehicle.companyId) == null) {
                    return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED) to null
                }

                val active = vehicleAssignmentRepository.findActiveForVendor(vendorUserId)
                if (active == null || active.id != initial.id) {
                    return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED) to null
                }
                val vehicle = vehicleRepository.findByIdForUpdate(active.vehicleId)
                if (vehicle?.companyId != initialVehicle.companyId) {
                    return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED) to null
                }

                if (!endAssignmentState(active)) {
                    return@transactional AppResult.Error(VehicleAssignmentError.NOT_LINKED) to null
                }
                syncService.recordChange(
                    userId = active.vendorUserId,
                    entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                    entityId = active.id,
                    operation = SyncOperation.UPSERT,
                )

                AppResult.Success(Unit) to active
            }
        endedAssignment?.let { notifyEndedAssignments(listOf(it)) }
        return result
    }

    @Suppress("ReturnCount")
    override suspend fun endActiveAssignment(
        adminUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> {
        val (result, endedAssignment) =
            tx.transactional {
                val initialVehicle =
                    vehicleRepository.findById(vehicleId)
                        ?: return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND) to null

                if (companyRepository.findByIdForUpdate(initialVehicle.companyId) == null) {
                    return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND) to null
                }
                val vehicle =
                    vehicleRepository.findByIdForUpdate(vehicleId)
                        ?: return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND) to null
                if (vehicle.companyId != initialVehicle.companyId) {
                    return@transactional AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND) to null
                }

                // Both "not a member at all" and "a member but not an admin" collapse to the same
                // NOT_ADMIN error here — this feature has never distinguished the two, unlike company/
                // vehicle management, which do.
                when (membershipRepository.requireAdminMembership(vehicle.companyId, adminUserId)) {
                    MembershipCheck.NotAMember, MembershipCheck.NotAdmin -> {
                        return@transactional AppResult.Error(VehicleAssignmentError.NOT_ADMIN) to null
                    }

                    is MembershipCheck.Admin -> {}
                }

                val active =
                    vehicleAssignmentRepository.findActiveForVehicle(vehicleId)
                        ?: return@transactional AppResult.Error(VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT) to null

                if (!endAssignmentState(active)) {
                    return@transactional AppResult.Error(VehicleAssignmentError.NO_ACTIVE_ASSIGNMENT) to null
                }
                syncService.recordChange(
                    userId = active.vendorUserId,
                    entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                    entityId = active.id,
                    operation = SyncOperation.UPSERT,
                )

                AppResult.Success(Unit) to active
            }
        endedAssignment?.let { notifyEndedAssignments(listOf(it)) }
        return result
    }

    override suspend fun endActiveAssignmentForVendorInCompany(
        vendorUserId: Int,
        companyId: Int,
    ): VehicleAssignment? =
        tx.transactional {
            if (companyRepository.findByIdForUpdate(companyId) == null) return@transactional null

            // A vendor can only ever have ONE active assignment at a time, globally (the DB's
            // partial unique index on vendor_user_id) — so there's at most one to check, and it
            // only matters here if it happens to be for a vehicle owned by *this* company.
            val active = vehicleAssignmentRepository.findActiveForVendor(vendorUserId) ?: return@transactional null
            val vehicle = vehicleRepository.findByIdForUpdate(active.vehicleId)
            if (vehicle?.companyId != companyId) return@transactional null
            val current = vehicleAssignmentRepository.findActiveForVendor(vendorUserId)
            if (current?.id != active.id) return@transactional null

            active.takeIf { endAssignmentState(it) }
        }

    override suspend fun endAllActiveAssignmentsForCompany(companyId: Int): List<VehicleAssignment> =
        tx.transactional {
            if (companyRepository.findByIdForUpdate(companyId) == null) return@transactional emptyList()

            val activeAssignments =
                vehicleAssignmentRepository
                    .findAllForCompany(companyId)
                    .filter(VehicleAssignment::isActive)
                    .sortedBy(VehicleAssignment::vehicleId)
            activeAssignments.mapNotNull { candidate ->
                val vehicle = vehicleRepository.findByIdForUpdate(candidate.vehicleId)
                if (vehicle == null || vehicle.companyId != companyId) {
                    return@mapNotNull null
                }

                val active = vehicleAssignmentRepository.findActiveForVehicle(vehicle.id)
                if (active == null || active.id != candidate.id) {
                    return@mapNotNull null
                }

                active.takeIf { endAssignmentState(it) }
            }
        }

    override suspend fun findAllForCompany(companyId: Int): List<VehicleAssignment> =
        tx.transactional { vehicleAssignmentRepository.findAllForCompany(companyId) }

    override fun notifyEndedAssignments(assignments: List<VehicleAssignment>) {
        assignments.distinctBy(VehicleAssignment::id).forEach { assignment ->
            hub.markOffline(assignment.vehicleId, assignment.id)
        }
    }

    private suspend fun endAssignmentState(assignment: VehicleAssignment): Boolean =
        vehicleAssignmentRepository.endAssignment(assignment.id, Instant.now())

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? =
        tx.transactional { vehicleAssignmentRepository.findActiveForVendor(vendorUserId) }

    override suspend fun findActiveDetailsForVendor(vendorUserId: Int): ActiveVehicleAssignment? =
        tx.transactional { vehicleAssignmentRepository.findActiveDetailsForVendor(vendorUserId) }

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? =
        tx.transactional { vehicleAssignmentRepository.findActiveForVehicle(vehicleId) }

    override suspend fun recordTelemetryReceived(
        assignmentId: Int,
        vendorUserId: Int,
        receivedAt: Instant,
    ): Boolean =
        tx.transactional {
            vehicleAssignmentRepository.recordTelemetryReceived(assignmentId, vendorUserId, receivedAt)
        }

    override suspend fun endActiveAssignmentForVendor(vendorUserId: Int): VehicleAssignment? =
        tx.transactional {
            val initial = vehicleAssignmentRepository.findActiveForVendor(vendorUserId) ?: return@transactional null
            val initialVehicle =
                vehicleRepository.findById(initial.vehicleId)
                    ?: return@transactional null
            if (companyRepository.findByIdForUpdate(initialVehicle.companyId) == null) return@transactional null

            val vehicle =
                vehicleRepository
                    .findByIdForUpdate(initial.vehicleId)
                    ?.takeIf { it.companyId == initialVehicle.companyId }
                    ?: return@transactional null
            val active = vehicleAssignmentRepository.findActiveForVendor(vendorUserId)
            if (active == null) return@transactional null
            if (active.id != initial.id) return@transactional null
            if (active.vehicleId != vehicle.id) return@transactional null

            val endedAt = Instant.now()
            if (!vehicleAssignmentRepository.endAssignment(active.id, endedAt)) return@transactional null

            syncService.recordChange(
                userId = active.vendorUserId,
                entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                entityId = active.id,
                operation = SyncOperation.UPSERT,
            )
            active.copy(endedAt = endedAt)
        }
}
