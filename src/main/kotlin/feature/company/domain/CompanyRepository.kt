/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.domain

import com.mozgobolt.feature.company.domain.model.Company

/** Persistence operations for company records and invite codes. */
interface CompanyRepository {
    /** Creates a company with the supplied name and invite code. */
    suspend fun create(
        name: String,
        inviteCode: String,
    ): Company

    /** Finds the company associated with an invite code. */
    suspend fun findByInviteCode(inviteCode: String): Company?

    /** Finds a company by its identifier. */
    suspend fun findById(companyId: Int): Company?

    /**
     * Reads and locks the company row for the remainder of the caller's transaction.
     * Membership mutations use this stable row to serialize company-wide invariants.
     */
    suspend fun findByIdForUpdate(companyId: Int): Company?

    /** Replaces a company's name and returns the updated record, if it exists. */
    suspend fun rename(
        companyId: Int,
        newName: String,
    ): Company?

    /** Replaces a company's invite code and returns the updated record, if it exists. */
    suspend fun updateInviteCode(
        companyId: Int,
        newInviteCode: String,
    ): Company?

    /** Deletes a company and reports whether a row was removed. */
    suspend fun delete(companyId: Int): Boolean
}
