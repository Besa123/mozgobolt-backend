package com.mozgobolt.feature.companyFavorite.routing.dto.response

import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import kotlinx.serialization.Serializable

@Serializable
data class CompanyFavoriteResponseDto(
    val companyId: Int,
)

fun CompanyFavorite.toResponseDto() = CompanyFavoriteResponseDto(companyId = companyId)
