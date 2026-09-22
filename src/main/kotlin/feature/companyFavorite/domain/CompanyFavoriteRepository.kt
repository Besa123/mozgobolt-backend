package com.mozgobolt.feature.companyFavorite.domain

import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite

interface CompanyFavoriteRepository {
    /**
     * `null` means this (userId, companyId) pair was already favorited — the DB's
     * `UNIQUE(user_id, company_id)` index is the actual guard, mirroring
     * [com.mozgobolt.feature.company.domain.CompanyMembershipRepository.addIfAbsent]'s contract.
     * The caller treats this as a no-op success, not an error — favoriting is idempotent.
     */
    suspend fun addIfAbsent(
        userId: Int,
        companyId: Int,
    ): CompanyFavorite?

    /** Returns whether a row was actually removed, so the caller can skip recording a sync
     * change for an un-favorite that had nothing to do. */
    suspend fun remove(
        userId: Int,
        companyId: Int,
    ): Boolean

    suspend fun findAllForUser(userId: Int): List<CompanyFavorite>

    /**
     * Every user id that has favorited [companyId] — the first half of the candidate-narrowing
     * query [com.mozgobolt.feature.proximityNotification.service.ProximityAlertServiceI] relies
     * on to avoid scanning every buyer's saved locations on every telemetry point. Backed by the
     * existing index on `company_favorites(company_id)`.
     */
    suspend fun findUserIdsFavoritingCompany(companyId: Int): List<Int>
}
