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
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.company.domain.requireAdminMembership
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService

/** Below (not equal to) this many admins, removing/demoting one more would leave zero. */
private const val MIN_ADMINS_BEFORE_REMOVING_ONE_MORE = 2

class CompanyServiceI(
    private val companyRepository: CompanyRepository,
    private val membershipRepository: CompanyMembershipRepository,
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

            syncService.recordChange(creatorUserId, SyncEntityType.COMPANY, company.id, SyncOperation.UPSERT)
            syncService.recordChange(
                creatorUserId,
                SyncEntityType.COMPANY_MEMBERSHIP,
                company.id,
                SyncOperation.UPSERT,
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
            val company =
                companyRepository.findByInviteCode(inviteCode)
                    ?: return@transactional AppResult.Error(CompanyError.INVALID_INVITE_CODE)

            // insertIgnore-backed: the actual guard against a double-submitted join racing itself
            // is the DB's UNIQUE(company_id, user_id) index, not this call alone.
            membershipRepository.addIfAbsent(company.id, userId, CompanyRole.MEMBER)
                ?: return@transactional AppResult.Error(CompanyError.ALREADY_MEMBER)

            syncService.recordChange(userId, SyncEntityType.COMPANY, company.id, SyncOperation.UPSERT)
            syncService.recordChange(userId, SyncEntityType.COMPANY_MEMBERSHIP, company.id, SyncOperation.UPSERT)

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
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            val renamed =
                companyRepository.rename(companyId, trimmedName)
                    ?: return@transactional AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            syncService.recordChange(adminUserId, SyncEntityType.COMPANY, companyId, SyncOperation.UPSERT)

            AppResult.Success(renamed)
        }
    }

    @Suppress("ReturnCount")
    override suspend fun deleteCompany(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError> =
        tx.transactional {
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            // Must happen before the delete below: once the DB cascades vehicles away, there's
            // nothing left to look up an active assignment's vehicle against, and any vehicle
            // that was actively being driven would otherwise stay stuck on the live map forever.
            vehicleAssignmentService.endAllActiveAssignmentsForCompany(companyId)

            // The DB cascades vehicles, vehicle_assignments, vehicle_locations and
            // company_memberships for us (see the V2 migration) — nothing else to clean up here.
            if (!companyRepository.delete(companyId)) {
                return@transactional AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            }
            syncService.recordChange(adminUserId, SyncEntityType.COMPANY, companyId, SyncOperation.DELETE)

            AppResult.Success(Unit)
        }

    @Suppress("ReturnCount")
    override suspend fun regenerateInviteCode(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Company, CompanyError> =
        tx.transactional {
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            val updated =
                companyRepository.updateInviteCode(
                    companyId,
                    SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                ) ?: return@transactional AppResult.Error(CompanyError.COMPANY_NOT_FOUND)
            syncService.recordChange(adminUserId, SyncEntityType.COMPANY, companyId, SyncOperation.UPSERT)

            AppResult.Success(updated)
        }

    @Suppress("ReturnCount")
    override suspend fun listMembers(
        userId: Int,
        companyId: Int,
    ): AppResult<List<CompanyMembership>, CompanyError> =
        tx.transactional {
            membershipRepository.find(companyId, userId)
                ?: return@transactional AppResult.Error(CompanyError.NOT_A_MEMBER)

            AppResult.Success(membershipRepository.findAllForCompany(companyId))
        }

    override suspend fun promoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> = changeRole(adminUserId, companyId, targetUserId, CompanyRole.ADMIN)

    override suspend fun demoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> = changeRole(adminUserId, companyId, targetUserId, CompanyRole.MEMBER)

    @Suppress("ReturnCount")
    private suspend fun changeRole(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
        newRole: CompanyRole,
    ): AppResult<Unit, CompanyError> =
        tx.transactional {
            requireAdmin(adminUserId, companyId)?.let { return@transactional it }

            val target =
                membershipRepository.find(companyId, targetUserId)
                    ?: return@transactional AppResult.Error(CompanyError.TARGET_NOT_A_MEMBER)
            if (target.role == newRole) return@transactional AppResult.Success(Unit)

            if (target.role == CompanyRole.ADMIN && newRole == CompanyRole.MEMBER) {
                requireNotLastAdmin(companyId)?.let { return@transactional it }
            }

            membershipRepository.updateRole(companyId, targetUserId, newRole)
            syncService.recordChange(targetUserId, SyncEntityType.COMPANY_MEMBERSHIP, companyId, SyncOperation.UPSERT)

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
    @Suppress("ReturnCount")
    private suspend fun removeMembership(
        companyId: Int,
        targetUserId: Int,
        requireAdminUserId: Int?,
    ): AppResult<Unit, CompanyError> =
        tx.transactional {
            if (requireAdminUserId != null) {
                requireAdmin(requireAdminUserId, companyId)?.let { return@transactional it }
            }

            val target =
                membershipRepository.find(companyId, targetUserId)
                    ?: return@transactional AppResult.Error(CompanyError.TARGET_NOT_A_MEMBER)

            if (target.role == CompanyRole.ADMIN) {
                requireNotLastAdmin(companyId)?.let { return@transactional it }
            }

            // Ends the target's active assignment if (and only if) it's for one of *this*
            // company's vehicles — delegated to VehicleAssignmentService rather than touched
            // here directly, so this always goes through the one place that also clears the
            // vehicle from the live map (see VehicleAssignmentServiceI.endAssignmentAndNotify).
            vehicleAssignmentService.endActiveAssignmentForVendorInCompany(targetUserId, companyId)

            membershipRepository.remove(companyId, targetUserId)
            syncService.recordChange(targetUserId, SyncEntityType.COMPANY_MEMBERSHIP, companyId, SyncOperation.DELETE)

            AppResult.Success(Unit)
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
     * Rejects an admin-count-reducing action if the company currently has fewer than
     * [MIN_ADMINS_BEFORE_REMOVING_ONE_MORE] admins. This is a plain read-then-check inside the
     * same transaction, not a row-locked one — two concurrent demotes of two *different* admins on
     * a two-admin company could theoretically both pass this check before either commits and leave
     * zero admins. Accepted: this is a narrow, low-probability race for a small-scale app (the same
     * risk tolerance already agreed for this project elsewhere, e.g. the orphaned-company-row case
     * in the old create/join race), not worth a locking- or trigger-based guard that would be
     * genuine over-engineering for the actual threat here.
     */
    private suspend fun requireNotLastAdmin(companyId: Int): AppResult.Error<CompanyError>? =
        AppResult.Error(CompanyError.LAST_ADMIN).takeIf {
            membershipRepository.countAdmins(companyId) < MIN_ADMINS_BEFORE_REMOVING_ONE_MORE
        }
}
