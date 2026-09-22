package com.mozgobolt.feature.company.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyMembership

interface CompanyService {
    suspend fun createCompany(
        creatorUserId: Int,
        name: String,
    ): AppResult<Company, CompanyError>

    suspend fun joinCompany(
        userId: Int,
        inviteCode: String,
    ): AppResult<Company, CompanyError>

    suspend fun renameCompany(
        adminUserId: Int,
        companyId: Int,
        newName: String,
    ): AppResult<Company, CompanyError>

    suspend fun deleteCompany(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError>

    /** Replaces the company's invite code with a freshly generated one — the old code stops
     * working immediately, since [joinCompany] resolves a company strictly by exact invite-code
     * match. Use when a code has leaked beyond its intended audience. */
    suspend fun regenerateInviteCode(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Company, CompanyError>

    suspend fun listMembers(
        userId: Int,
        companyId: Int,
    ): AppResult<List<CompanyMembership>, CompanyError>

    suspend fun promoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError>

    suspend fun demoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError>

    suspend fun removeMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError>

    suspend fun leaveCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError>
}
