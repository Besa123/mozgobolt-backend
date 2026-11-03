/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary

/** Company and membership use cases with authorization enforced by the service layer. */
interface CompanyService {
    /** Creates a company and grants its creator administrative membership. */
    suspend fun createCompany(
        creatorUserId: Int,
        name: String,
    ): AppResult<Company, CompanyError>

    /** Adds a user to the company identified by an invite code. */
    suspend fun joinCompany(
        userId: Int,
        inviteCode: String,
    ): AppResult<Company, CompanyError>

    /** Renames a company when the caller has sufficient administrative access. */
    suspend fun renameCompany(
        adminUserId: Int,
        companyId: Int,
        newName: String,
    ): AppResult<Company, CompanyError>

    /** Deletes a company when the caller has sufficient administrative access. */
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

    /** Reads the current invite code when the caller is an admin of the company. */
    suspend fun getCurrentInviteCode(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<String, CompanyError>

    /** Lists a company's members for a user permitted to view them. */
    suspend fun listMembers(
        userId: Int,
        companyId: Int,
    ): AppResult<List<CompanyMemberSummary>, CompanyError>

    /** Lists the companies and membership roles associated with a user. */
    suspend fun listCompaniesForUser(userId: Int): AppResult<List<CompanyMembershipSummary>, CompanyError>

    /** Promotes a company member when the caller is authorized to manage membership. */
    suspend fun promoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError>

    /** Demotes a company member when the caller is authorized to manage membership. */
    suspend fun demoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError>

    /** Removes a member when the caller is authorized to manage membership. */
    suspend fun removeMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError>

    /** Removes the caller from the specified company. */
    suspend fun leaveCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError>
}
