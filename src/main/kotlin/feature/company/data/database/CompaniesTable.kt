package com.mozgobolt.feature.company.data.database

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object CompaniesTable : IntIdTable("companies") {
    val name = varchar("name", DISPLAY_NAME_MAX_LENGTH)
    val inviteCode = varchar("invite_code", CompanyConstraints.INVITE_CODE_LENGTH).uniqueIndex()
    val createdAt = timestamp("created_at")
}

class CompanyEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var name by CompaniesTable.name
    var inviteCode by CompaniesTable.inviteCode
    var createdAt by CompaniesTable.createdAt

    companion object : IntEntityClass<CompanyEntity>(CompaniesTable)
}
