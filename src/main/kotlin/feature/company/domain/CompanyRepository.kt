package com.mozgobolt.feature.company.domain

import com.mozgobolt.feature.company.domain.model.Company

interface CompanyRepository {
    suspend fun create(
        name: String,
        inviteCode: String,
    ): Company

    suspend fun findByInviteCode(inviteCode: String): Company?

    suspend fun findById(companyId: Int): Company?

    suspend fun rename(
        companyId: Int,
        newName: String,
    ): Company?

    suspend fun updateInviteCode(
        companyId: Int,
        newInviteCode: String,
    ): Company?

    suspend fun delete(companyId: Int): Boolean
}
