package com.mozgobolt.feature.companyFavorite.data.database

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object CompanyFavoritesTable : IntIdTable("company_favorites") {
    val userId = integer("user_id")
    val companyId = integer("company_id")
    val createdAt = timestamp("created_at")
}

class CompanyFavoriteEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var userId by CompanyFavoritesTable.userId
    var companyId by CompanyFavoritesTable.companyId
    var createdAt by CompanyFavoritesTable.createdAt

    companion object : IntEntityClass<CompanyFavoriteEntity>(CompanyFavoritesTable)
}
