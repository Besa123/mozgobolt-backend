package com.mozgobolt.feature.company.data.database

import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

// Exposed mappings define the current schema; Flyway migrations evolve the deployed schema.
data object CompanyMembershipsTable : IntIdTable("company_memberships") {
    val companyId =
        integer("company_id").references(
            CompaniesTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val userId =
        integer("user_id")
            .references(
                UsersTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).index()
    val role = enumerationByName<CompanyRole>("role", CompanyConstraints.ROLE_COLUMN_LENGTH)
    val joinedAt = timestamp("joined_at")

    init {
        uniqueIndex(companyId, userId)
    }
}

class CompanyMembershipEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var companyId by CompanyMembershipsTable.companyId
    var userId by CompanyMembershipsTable.userId
    var role by CompanyMembershipsTable.role
    var joinedAt by CompanyMembershipsTable.joinedAt

    companion object : IntEntityClass<CompanyMembershipEntity>(CompanyMembershipsTable)
}
