/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.companyFavorite.data.database

import com.mozgobolt.feature.company.data.database.CompaniesTable
import com.mozgobolt.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

data object CompanyFavoritesTable : IntIdTable("company_favorites") {
    val userId =
        integer("user_id").references(
            UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val companyId =
        integer("company_id")
            .references(
                CompaniesTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).index()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(userId, companyId)
    }
}

class CompanyFavoriteEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var userId by CompanyFavoritesTable.userId
    var companyId by CompanyFavoritesTable.companyId
    var createdAt by CompanyFavoritesTable.createdAt

    companion object : IntEntityClass<CompanyFavoriteEntity>(CompanyFavoritesTable)
}
