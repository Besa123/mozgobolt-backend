package com.mozgobolt.feature.companyFavorite.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError

interface CompanyFavoriteService {
    suspend fun favoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError>

    suspend fun unfavoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError>

    suspend fun listFavorites(userId: Int): List<CompanyFavorite>
}
