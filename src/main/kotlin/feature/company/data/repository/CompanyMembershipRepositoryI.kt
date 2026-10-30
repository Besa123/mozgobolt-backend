package com.mozgobolt.feature.company.data.repository

import com.mozgobolt.feature.company.data.database.CompaniesTable
import com.mozgobolt.feature.company.data.database.CompanyMembershipEntity
import com.mozgobolt.feature.company.data.database.CompanyMembershipsTable
import com.mozgobolt.feature.company.data.mapper.toCompanyMembership
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
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

    override suspend fun findAllForCompany(companyId: Int): List<CompanyMemberSummary> =
        (CompanyMembershipsTable innerJoin UsersTable)
            .select(
                CompanyMembershipsTable.userId,
                UsersTable.name,
                CompanyMembershipsTable.role,
            ).where { CompanyMembershipsTable.companyId eq companyId }
            .orderBy(CompanyMembershipsTable.joinedAt to SortOrder.ASC, CompanyMembershipsTable.id to SortOrder.ASC)
            .map { row ->
                CompanyMemberSummary(
                    userId = row[CompanyMembershipsTable.userId],
                    name = row[UsersTable.name],
                    role = row[CompanyMembershipsTable.role],
                )
            }

    override suspend fun findCompanySummariesForUser(userId: Int): List<CompanyMembershipSummary> =
        (CompanyMembershipsTable innerJoin CompaniesTable)
            .select(CompanyMembershipsTable.companyId, CompaniesTable.name, CompanyMembershipsTable.role)
            .where { CompanyMembershipsTable.userId eq userId }
            .orderBy(CompanyMembershipsTable.joinedAt to SortOrder.ASC, CompanyMembershipsTable.id to SortOrder.ASC)
            .map { row ->
                CompanyMembershipSummary(
                    companyId = row[CompanyMembershipsTable.companyId],
                    companyName = row[CompaniesTable.name],
                    membershipRole = row[CompanyMembershipsTable.role],
                )
            }

    override suspend fun countAdmins(companyId: Int): Int {
        val adminCount = CompanyMembershipsTable.id.count()
        return CompanyMembershipsTable
            .select(adminCount)
            .where {
                (CompanyMembershipsTable.companyId eq companyId) and (CompanyMembershipsTable.role eq CompanyRole.ADMIN)
            }.single()[adminCount]
            .toInt()
    }

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

    override suspend fun remove(
        companyId: Int,
        userId: Int,
    ) {
        CompanyMembershipsTable.deleteWhere {
            (CompanyMembershipsTable.companyId eq companyId) and (CompanyMembershipsTable.userId eq userId)
        }
    }
}
