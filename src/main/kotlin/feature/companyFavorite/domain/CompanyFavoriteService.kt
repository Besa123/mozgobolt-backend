/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.companyFavorite.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError

/** Use cases for managing a user's favorite companies. */
interface CompanyFavoriteService {
    /** Adds a company to the user's favorites. */
    suspend fun favoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError>

    /** Removes a company from the user's favorites. */
    suspend fun unfavoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError>

    /** Lists the companies favorited by the user. */
    suspend fun listFavorites(userId: Int): List<CompanyFavorite>
}
