package com.mozgobolt.feature.company.data.repository

import com.mozgobolt.feature.company.data.database.CompanyMembershipEntity
import com.mozgobolt.feature.company.data.database.CompanyMembershipsTable
import com.mozgobolt.feature.company.data.mapper.toCompanyMembership
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class CompanyMembershipRepositoryI : CompanyMembershipRepository {
    // insertIgnore (Postgres `ON CONFLICT DO NOTHING`), not a caught unique-constraint exception:
    // a violation aborts the *entire* Postgres transaction, so this must never raise the error in
    // the first place — same proven pattern as VehicleAssignmentRepositoryI.startAssignment.
    override suspend fun addIfAbsent(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
    ): CompanyMembership? {
        val insertedCount =
            CompanyMembershipsTable
                .insertIgnore {
                    it[CompanyMembershipsTable.companyId] = companyId
                    it[CompanyMembershipsTable.userId] = userId
                    it[CompanyMembershipsTable.role] = role
                    it[CompanyMembershipsTable.joinedAt] = Instant.now()
                }.insertedCount

        return insertedCount.takeIf { it > 0 }?.let { find(companyId, userId) }
    }

    override suspend fun find(
        companyId: Int,
        userId: Int,
    ): CompanyMembership? =
        CompanyMembershipEntity
            .find {
                (CompanyMembershipsTable.companyId eq companyId) and (CompanyMembershipsTable.userId eq userId)
            }.firstOrNull()
            ?.toCompanyMembership()

    override suspend fun findAllForCompany(companyId: Int): List<CompanyMembership> =
        CompanyMembershipEntity
            .find { CompanyMembershipsTable.companyId eq companyId }
            .map { it.toCompanyMembership() }

    override suspend fun countAdmins(companyId: Int): Int =
        CompanyMembershipEntity
            .find {
                (CompanyMembershipsTable.companyId eq companyId) and (CompanyMembershipsTable.role eq CompanyRole.ADMIN)
            }.count()
            .toInt()

    override suspend fun updateRole(
        companyId: Int,
        userId: Int,
        role: CompanyRole,
    ) {
        CompanyMembershipsTable.update(
            where = { (CompanyMembershipsTable.companyId eq companyId) and (CompanyMembershipsTable.userId eq userId) },
        ) {
            it[CompanyMembershipsTable.role] = role
        }
    }

    // At most one row can ever match (the UNIQUE(company_id, user_id) index) — DAO-style delete
    // to stay consistent with how every other feature in this codebase removes rows.
    override suspend fun remove(
        companyId: Int,
        userId: Int,
    ) {
        CompanyMembershipEntity
            .find { (CompanyMembershipsTable.companyId eq companyId) and (CompanyMembershipsTable.userId eq userId) }
            .forEach { it.delete() }
    }
}
