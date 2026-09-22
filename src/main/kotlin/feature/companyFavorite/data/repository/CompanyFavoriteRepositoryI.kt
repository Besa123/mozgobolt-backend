package com.mozgobolt.feature.companyFavorite.data.repository

import com.mozgobolt.feature.companyFavorite.data.database.CompanyFavoriteEntity
import com.mozgobolt.feature.companyFavorite.data.database.CompanyFavoritesTable
import com.mozgobolt.feature.companyFavorite.data.mapper.toCompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.time.Instant

class CompanyFavoriteRepositoryI : CompanyFavoriteRepository {
    // insertIgnore (Postgres `ON CONFLICT DO NOTHING`), not a caught unique-constraint exception:
    // a violation aborts the *entire* Postgres transaction, so this must never raise the error in
    // the first place — same proven pattern as CompanyMembershipRepositoryI.addIfAbsent.
    override suspend fun addIfAbsent(
        userId: Int,
        companyId: Int,
    ): CompanyFavorite? {
        val insertedCount =
            CompanyFavoritesTable
                .insertIgnore {
                    it[CompanyFavoritesTable.userId] = userId
                    it[CompanyFavoritesTable.companyId] = companyId
                    it[CompanyFavoritesTable.createdAt] = Instant.now()
                }.insertedCount

        return insertedCount.takeIf { it > 0 }?.let { find(userId, companyId) }
    }

    override suspend fun remove(
        userId: Int,
        companyId: Int,
    ): Boolean {
        val matches = find(userId, companyId) ?: return false
        CompanyFavoriteEntity[matches.id].delete()
        return true
    }

    override suspend fun findAllForUser(userId: Int): List<CompanyFavorite> =
        CompanyFavoriteEntity
            .find { CompanyFavoritesTable.userId eq userId }
            .map { it.toCompanyFavorite() }

    override suspend fun findUserIdsFavoritingCompany(companyId: Int): List<Int> =
        CompanyFavoriteEntity
            .find { CompanyFavoritesTable.companyId eq companyId }
            .map { it.userId }

    private suspend fun find(
        userId: Int,
        companyId: Int,
    ): CompanyFavorite? =
        CompanyFavoriteEntity
            .find { (CompanyFavoritesTable.userId eq userId) and (CompanyFavoritesTable.companyId eq companyId) }
            .firstOrNull()
            ?.toCompanyFavorite()
}
