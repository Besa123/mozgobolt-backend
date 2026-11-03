/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.CompanyService
import com.mozgobolt.feature.company.domain.MembershipCheck
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.company.domain.requireAdminMembership
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment

private const val MIN_ADMINS_BEFORE_REMOVING_ONE_MORE = 2

class CompanyServiceI(
    private val companyRepository: CompanyRepository,
    private val membershipRepository: CompanyMembershipRepository,
    private val companyFavoriteRepository: CompanyFavoriteRepository,
    private val vehicleAssignmentService: VehicleAssignmentService,
    private val syncService: SyncService,
    private val tx: TransactionalRunner,
) : CompanyService {
    override suspend fun createCompany(
        creatorUserId: Int,
        name: String,
    ): AppResult<Company, CompanyError> {
        val trimmedName = name.trim()

        return tx.transactional {
            val company =
                companyRepository.create(
                    name = trimmedName,
                    inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                )
            // No race to guard here (unlike joinCompany): this is a brand-new company id that
            // nothing else could possibly reference yet, so the first membership insert can never
            // collide with a concurrent one.
            membershipRepository.addIfAbsent(company.id, creatorUserId, CompanyRole.ADMIN)

            syncService.recordChange(
                userId = creatorUserId,
                entityType = SyncEntityType.COMPANY,
                entityId = company.id,
                operation = SyncOperation.UPSERT,
            )
            syncService.recordChange(
                userId = creatorUserId,
                entityType = SyncEntityType.COMPANY_MEMBERSHIP,
                entityId = company.id,
                operation = SyncOperation.UPSERT,
            )

            AppResult.Success(company)
        }
    }

    @Suppress("ReturnCount")
    override suspend fun joinCompany(
        userId: Int,
        inviteCode: String,
    ): AppResult<Company, CompanyError> =
        tx.transactional {
            val companyFoundByInviteCode =
                companyRepository.findByInviteCode(inviteCode)
                    ?: return@transactional AppResult.Error(CompanyError.INVALID_INVITE_CODE)
            val company =
                companyRepository
                    .findByIdForUpdate(companyFoundByInviteCode.id)
                    ?.takeIf { it.inviteCode == inviteCode }
                    ?: return@transactional AppResult.Error(CompanyError.INVALID_INVITE_CODE)

            // insertIgnore-backed: the actual guard against a double-submitted join racing itself
            // is the DB's UNIQUE(company_id, user_id) index. The company-row lock serializes this
            // use with invite-code rotation, and the code is rechecked after acquiring that lock.
            membershipRepository.addIfAbsent(company.id, userId, CompanyRole.MEMBER)
                ?: return@transactional AppResult.Error(CompanyError.ALREADY_MEMBER)

            syncService.recordChange(
                userId = userId,
                entityType = SyncEntityType.COMPANY,
                entityId = company.id,
                operation = SyncOperation.UPSERT,
            )
            syncService.recordChange(
                userId = userId,
                entityType = SyncEntityType.COMPANY_MEMBERSHIP,
                entityId = company.id,
                operation = SyncOperation.UPSERT,
            )

            AppResult.Success(company)
        }

    @Suppress("ReturnCount")
    override suspend fun renameCompany(
        adminUserId: Int,
        companyId: Int,
        newName: String,
    ): AppResult<Company, CompanyError> {
        val trimmedName = newName.trim()

        return tx.transactional {
            companyRepository.findByIdForUpdate(companyId)
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            val renamed =
                companyRepository.rename(companyId, trimmedName)
                    ?: return@transactional AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            syncService.recordChange(
                userId = adminUserId,
                entityType = SyncEntityType.COMPANY,
                entityId = companyId,
                operation = SyncOperation.UPSERT,
            )

            AppResult.Success(renamed)
        }
    }

    @Suppress("ReturnCount")
    override suspend fun deleteCompany(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError> {
        val (result, endedAssignments) =
            tx.transactional {
                requireAdmin(adminUserId, companyId)?.let {
                    return@transactional it to emptyList<VehicleAssignment>()
                }
                if (companyRepository.findByIdForUpdate(companyId) == null) {
                    return@transactional AppResult.Error(CompanyError.COMPANY_NOT_FOUND) to
                        emptyList<VehicleAssignment>()
                }
                requireAdmin(adminUserId, companyId)?.let {
                    return@transactional it to emptyList<VehicleAssignment>()
                }

                val favoriteUserIds = companyFavoriteRepository.findUserIdsFavoritingCompany(companyId).toSet()
                val memberUserIds = membershipRepository.findAllForCompany(companyId).map { it.userId }.toSet()
                val companyAssignments = vehicleAssignmentService.findAllForCompany(companyId)

                // End active sessions before the vehicle/assignment cascade removes the rows needed
                // to identify their live-map entries. The actual hub notification waits until commit.
                val endedAssignments = vehicleAssignmentService.endAllActiveAssignmentsForCompany(companyId)

                // Flyway V1 cascades company memberships, vehicles, assignments, locations, favorites,
                // and pings. Every assignment row is synced independently, including ended shifts
                // owned by vendors who have since left this company.
                check(companyRepository.delete(companyId)) {
                    "Company disappeared after its row was locked"
                }
                val assignmentsByVendor = companyAssignments.groupBy { it.vendorUserId }
                val affectedUserIds =
                    (assignmentsByVendor.keys + favoriteUserIds + memberUserIds).sorted()

                val syncChanges =
                    buildList {
                        affectedUserIds.forEach { userId ->
                            assignmentsByVendor[userId]
                                .orEmpty()
                                .sortedBy(VehicleAssignment::id)
                                .forEach { assignment ->
                                    add(
                                        SyncChange(
                                            userId = userId,
                                            entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                                            entityId = assignment.id,
                                            operation = SyncOperation.DELETE,
                                        ),
                                    )
                                }
                            if (userId in favoriteUserIds) {
                                add(
                                    SyncChange(
                                        userId = userId,
                                        entityType = SyncEntityType.COMPANY_FAVORITE,
                                        entityId = companyId,
                                        operation = SyncOperation.DELETE,
                                    ),
                                )
                            }
                            if (userId in memberUserIds) {
                                add(
                                    SyncChange(
                                        userId = userId,
                                        entityType = SyncEntityType.COMPANY,
                                        entityId = companyId,
                                        operation = SyncOperation.DELETE,
                                    ),
                                )
                                add(
                                    SyncChange(
                                        userId = userId,
                                        entityType = SyncEntityType.COMPANY_MEMBERSHIP,
                                        entityId = companyId,
                                        operation = SyncOperation.DELETE,
                                    ),
                                )
                            }
                        }
                    }
                syncService.recordChanges(syncChanges)

                AppResult.Success(Unit) to endedAssignments
            }

        if (result is AppResult.Success) vehicleAssignmentService.notifyEndedAssignments(endedAssignments)
        return result
    }

    @Suppress("ReturnCount")
    override suspend fun regenerateInviteCode(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Company, CompanyError> =
        tx.transactional {
            companyRepository.findByIdForUpdate(companyId)
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            val updated =
                companyRepository.updateInviteCode(
                    companyId,
                    SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                ) ?: return@transactional AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            syncService.recordChange(
                userId = adminUserId,
                entityType = SyncEntityType.COMPANY,
                entityId = companyId,
                operation = SyncOperation.UPSERT,
            )

            AppResult.Success(updated)
        }

    override suspend fun getCurrentInviteCode(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<String, CompanyError> =
        tx.transactional {
            val company =
                companyRepository.findByIdForUpdate(companyId)
                    ?: return@transactional AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            AppResult.Success(company.inviteCode)
        }

    @Suppress("ReturnCount")
    override suspend fun listMembers(
        userId: Int,
        companyId: Int,
    ): AppResult<List<CompanyMemberSummary>, CompanyError> =
        tx.transactional {
            membershipRepository.find(companyId, userId)
                ?: return@transactional AppResult.Error(CompanyError.NOT_A_MEMBER)

            AppResult.Success(membershipRepository.findAllForCompany(companyId))
        }

    override suspend fun listCompaniesForUser(userId: Int): AppResult<List<CompanyMembershipSummary>, CompanyError> =
        tx.transactional {
            AppResult.Success(membershipRepository.findCompanySummariesForUser(userId))
        }

    override suspend fun promoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> =
        changeRole(
            adminUserId = adminUserId,
            companyId = companyId,
            targetUserId = targetUserId,
            newRole = CompanyRole.ADMIN,
        )

    override suspend fun demoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> =
        changeRole(
            adminUserId = adminUserId,
            companyId = companyId,
            targetUserId = targetUserId,
            newRole = CompanyRole.MEMBER,
        )

    @Suppress("ReturnCount")
    private suspend fun changeRole(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
        newRole: CompanyRole,
    ): AppResult<Unit, CompanyError> =
        tx.transactional {
            companyRepository.findByIdForUpdate(companyId)
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            val target =
                membershipRepository.find(companyId, targetUserId)
                    ?: return@transactional AppResult.Error(CompanyError.TARGET_NOT_A_MEMBER)
            if (target.role == newRole) return@transactional AppResult.Success(Unit)

            if (target.role == CompanyRole.ADMIN && newRole == CompanyRole.MEMBER) {
                requireNotLastAdmin(companyId)?.let { return@transactional it }
            }

            membershipRepository.updateRole(companyId, targetUserId, newRole)
            syncService.recordChange(
                userId = targetUserId,
                entityType = SyncEntityType.COMPANY_MEMBERSHIP,
                entityId = companyId,
                operation = SyncOperation.UPSERT,
            )

            AppResult.Success(Unit)
        }

    override suspend fun removeMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> = removeMembership(companyId, targetUserId, requireAdminUserId = adminUserId)

    override suspend fun leaveCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError> = removeMembership(companyId, userId, requireAdminUserId = null)

    /**
     * Shared by [removeMember] (an admin removing someone else) and [leaveCompany] (self-service)
     * — the last-admin guard and the auto-end-active-session behavior apply identically either
     * way; only the caller-is-admin check differs.
     */
    @Suppress("CommentOverPrivateFunction", "ReturnCount")
    private suspend fun removeMembership(
        companyId: Int,
        targetUserId: Int,
        requireAdminUserId: Int?,
    ): AppResult<Unit, CompanyError> {
        val (result, endedAssignment) =
            tx.transactional {
                companyRepository.findByIdForUpdate(companyId)

                if (requireAdminUserId != null) {
                    requireAdmin(requireAdminUserId, companyId)?.let {
                        return@transactional it to null
                    }
                }

                val target =
                    membershipRepository.find(companyId, targetUserId)
                        ?: return@transactional AppResult.Error(CompanyError.TARGET_NOT_A_MEMBER) to null

                if (target.role == CompanyRole.ADMIN) {
                    requireNotLastAdmin(companyId)?.let {
                        return@transactional it to null
                    }
                }

                // The assignment service mutates durable state and returns the ended row, but
                // live-map notification is deferred until this owning transaction commits.
                val endedAssignment =
                    vehicleAssignmentService.endActiveAssignmentForVendorInCompany(targetUserId, companyId)

                membershipRepository.remove(companyId, targetUserId)
                val syncChanges =
                    buildList {
                        endedAssignment?.let { assignment ->
                            add(
                                SyncChange(
                                    userId = targetUserId,
                                    entityType = SyncEntityType.VEHICLE_ASSIGNMENT,
                                    entityId = assignment.id,
                                    operation = SyncOperation.UPSERT,
                                ),
                            )
                        }
                        add(
                            SyncChange(
                                userId = targetUserId,
                                entityType = SyncEntityType.COMPANY_MEMBERSHIP,
                                entityId = companyId,
                                operation = SyncOperation.DELETE,
                            ),
                        )
                    }
                syncService.recordChanges(syncChanges)

                AppResult.Success(Unit) to endedAssignment
            }

        if (result is AppResult.Success) {
            endedAssignment?.let {
                vehicleAssignmentService.notifyEndedAssignments(
                    listOf(it),
                )
            }
        }
        return result
    }

    private suspend fun requireAdmin(
        userId: Int,
        companyId: Int,
    ): AppResult.Error<CompanyError>? =
        when (membershipRepository.requireAdminMembership(companyId, userId)) {
            MembershipCheck.NotAMember -> AppResult.Error(CompanyError.NOT_A_MEMBER)
            MembershipCheck.NotAdmin -> AppResult.Error(CompanyError.NOT_ADMIN)
            is MembershipCheck.Admin -> null
        }

    /**
     * Rejects an admin-count-reducing action if fewer than two admins currently exist. Callers
     * hold the company's row lock for this transaction so concurrent membership mutations see
     * the committed admin count before deciding.
     */
    @Suppress("CommentOverPrivateFunction")
    private suspend fun requireNotLastAdmin(companyId: Int): AppResult.Error<CompanyError>? =
        AppResult.Error(CompanyError.LAST_ADMIN).takeIf {
            membershipRepository.countAdmins(companyId) < MIN_ADMINS_BEFORE_REMOVING_ONE_MORE
        }
}
