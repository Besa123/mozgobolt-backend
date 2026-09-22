package com.mozgobolt.feature.company.data.repository

import com.mozgobolt.feature.company.data.database.CompaniesTable
import com.mozgobolt.feature.company.data.database.CompanyEntity
import com.mozgobolt.feature.company.data.mapper.toCompany
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import org.jetbrains.exposed.v1.core.eq
import java.time.Instant

class CompanyRepositoryI : CompanyRepository {
    override suspend fun create(
        name: String,
        inviteCode: String,
    ): Company {
        val entity =
            CompanyEntity.new {
                this.name = name
                this.inviteCode = inviteCode
                this.createdAt = Instant.now()
            }
        return entity.toCompany()
    }

    override suspend fun findByInviteCode(inviteCode: String): Company? =
        CompanyEntity
            .find { CompaniesTable.inviteCode eq inviteCode }
            .firstOrNull()
            ?.toCompany()

    override suspend fun findById(companyId: Int): Company? =
        CompanyEntity
            .find { CompaniesTable.id eq companyId }
            .firstOrNull()
            ?.toCompany()

    override suspend fun rename(
        companyId: Int,
        newName: String,
    ): Company? {
        val entity = CompanyEntity.findById(companyId) ?: return null
        entity.name = newName
        return entity.toCompany()
    }

    override suspend fun updateInviteCode(
        companyId: Int,
        newInviteCode: String,
    ): Company? {
        val entity = CompanyEntity.findById(companyId) ?: return null
        entity.inviteCode = newInviteCode
        return entity.toCompany()
    }

    override suspend fun delete(companyId: Int): Boolean {
        val entity = CompanyEntity.findById(companyId) ?: return false
        entity.delete()
        return true
    }
}
