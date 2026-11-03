/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.companyFavorite.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation

class CompanyFavoriteServiceI(
    private val favoriteRepository: CompanyFavoriteRepository,
    private val proximityNotificationRepository: ProximityNotificationRepository,
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
            companyRepository.findByIdForUpdate(companyId)
                ?: return@transactional AppResult.Error(CompanyFavoriteError.COMPANY_NOT_FOUND)

            // Idempotent: favoriting an already-favorited company is a no-op success, not an
            // error — addIfAbsent returning null just means it was already there.
            if (favoriteRepository.addIfAbsent(userId, companyId) != null) {
                // The company ID is the stable favorite key exposed to clients; the row ID is internal.
                syncService.recordChange(
                    userId = userId,
                    entityType = SyncEntityType.COMPANY_FAVORITE,
                    entityId = companyId,
                    operation = SyncOperation.UPSERT,
                )
            }

            AppResult.Success(Unit)
        }

    override suspend fun unfavoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError> =
        tx.transactional {
            companyRepository.findByIdForUpdate(companyId)
                ?: return@transactional AppResult.Error(CompanyFavoriteError.COMPANY_NOT_FOUND)

            val wasRemoved = favoriteRepository.remove(userId, companyId)
            if (wasRemoved) {
                proximityNotificationRepository.deleteForUserAndCompany(userId, companyId)
                syncService.recordChange(
                    userId = userId,
                    entityType = SyncEntityType.COMPANY_FAVORITE,
                    entityId = companyId,
                    operation = SyncOperation.DELETE,
                )
            }

            AppResult.Success(Unit)
        }

    override suspend fun listFavorites(userId: Int): List<CompanyFavorite> =
        tx.transactional { favoriteRepository.findAllForUser(userId) }
}
