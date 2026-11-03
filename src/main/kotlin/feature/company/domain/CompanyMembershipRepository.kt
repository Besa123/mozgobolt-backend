/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.domain

import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import com.mozgobolt.feature.company.domain.model.CompanyRole

/** Persistence operations for company membership and role state. */
interface CompanyMembershipRepository {
    /**
     * `null` means a concurrent request already inserted this exact (companyId, userId) pair
     * first — the DB's `UNIQUE(company_id, user_id)` index is the actual guard, mirroring
     * [com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository.startAssignment]'s
     * contract exactly. The caller turns that into
     * [com.mozgobolt.feature.company.domain.model.CompanyError.ALREADY_MEMBER] rather than letting
     * a raw constraint violation surface.
     */
    suspend fun addIfAbsent(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
    ): CompanyMembership?

    /** Finds a user's membership in a company. */
    suspend fun find(
        companyId: Int,
        userId: Int,
    ): CompanyMembership?

    /** Lists the members of a company. */
    suspend fun findAllForCompany(companyId: Int): List<CompanyMemberSummary>

    /** Lists the companies and roles associated with a user. */
    suspend fun findCompanySummariesForUser(userId: Int): List<CompanyMembershipSummary>

    /** Counts administrators currently assigned to a company. */
    suspend fun countAdmins(companyId: Int): Int

    /** Replaces a member's role in a company. */
    suspend fun updateRole(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
    )

    /** Removes a user's membership from a company. */
    suspend fun remove(
        companyId: Int,
        userId: Int,
    )
}
