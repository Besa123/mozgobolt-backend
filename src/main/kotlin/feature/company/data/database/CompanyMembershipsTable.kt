package com.mozgobolt.feature.company.data.database

import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object CompanyMembershipsTable : IntIdTable("company_memberships") {
    val companyId = integer("company_id")
    val userId = integer("user_id")
    val role = enumerationByName<CompanyRole>("role", CompanyConstraints.ROLE_COLUMN_LENGTH)
    val joinedAt = timestamp("joined_at")
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
