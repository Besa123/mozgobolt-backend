package com.mozgobolt.feature.companyFavorite.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation

class CompanyFavoriteServiceI(
    private val favoriteRepository: CompanyFavoriteRepository,
    private val companyRepository: CompanyRepository,
    private val syncService: SyncService,
    private val tx: TransactionalRunner,
) : CompanyFavoriteService {
    @Suppress("ReturnCount")
    override suspend fun favoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError> =
        tx.transactional {
            companyRepository.findById(companyId)
                ?: return@transactional AppResult.Error(CompanyFavoriteError.COMPANY_NOT_FOUND)

            // Idempotent: favoriting an already-favorited company is a no-op success, not an
            // error — addIfAbsent returning null just means it was already there.
            val favorite = favoriteRepository.addIfAbsent(userId, companyId)
            if (favorite != null) {
                syncService.recordChange(userId, SyncEntityType.COMPANY_FAVORITE, favorite.id, SyncOperation.UPSERT)
            }

            AppResult.Success(Unit)
        }

    override suspend fun unfavoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError> =
        tx.transactional {
            val removed = favoriteRepository.remove(userId, companyId)
            if (removed) {
                syncService.recordChange(userId, SyncEntityType.COMPANY_FAVORITE, companyId, SyncOperation.DELETE)
            }

            AppResult.Success(Unit)
        }

    override suspend fun listFavorites(userId: Int): List<CompanyFavorite> =
        tx.transactional { favoriteRepository.findAllForUser(userId) }
}
