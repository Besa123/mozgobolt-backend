package com.mozgobolt.feature.companyFavorite.domain.model

import java.time.Instant

data class CompanyFavorite(
    val id: Int,
    val userId: Int,
    val companyId: Int,
    val createdAt: Instant,
)
