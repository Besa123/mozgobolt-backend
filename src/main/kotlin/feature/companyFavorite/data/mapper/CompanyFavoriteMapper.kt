package com.mozgobolt.feature.companyFavorite.data.mapper

import com.mozgobolt.feature.companyFavorite.data.database.CompanyFavoriteEntity
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite

fun CompanyFavoriteEntity.toCompanyFavorite() =
    CompanyFavorite(
        id = id.value,
        userId = userId,
        companyId = companyId,
        createdAt = createdAt,
    )
