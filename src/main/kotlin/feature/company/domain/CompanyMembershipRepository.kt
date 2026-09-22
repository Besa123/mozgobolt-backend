package com.mozgobolt.feature.company.domain

import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole

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

    suspend fun find(
        companyId: Int,
        userId: Int,
    ): CompanyMembership?

    suspend fun findAllForCompany(companyId: Int): List<CompanyMembership>

    suspend fun countAdmins(companyId: Int): Int

    suspend fun updateRole(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
    )

    suspend fun remove(
        companyId: Int,
        userId: Int,
    )
}
