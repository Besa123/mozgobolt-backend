package com.mozgobolt.feature.company.routing.dto.response

import com.mozgobolt.feature.company.domain.model.CompanyMembership
import kotlinx.serialization.Serializable

@Serializable
data class CompanyMembershipResponseDto(
    val userId: Int,
    val role: String,
)

fun CompanyMembership.toResponseDto() =
    CompanyMembershipResponseDto(
        userId = userId,
        role = role.name,
    )
