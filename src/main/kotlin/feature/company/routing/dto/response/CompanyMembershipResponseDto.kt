package com.mozgobolt.feature.company.routing.dto.response

import com.mozgobolt.feature.company.domain.model.CompanyMemberSummary
import kotlinx.serialization.Serializable

@Serializable
data class CompanyMembershipResponseDto(
    val userId: Int,
    val name: String,
    val role: String,
)

fun CompanyMemberSummary.toResponseDto() =
    CompanyMembershipResponseDto(
        userId = userId,
        name = name,
        role = role.name,
    )
