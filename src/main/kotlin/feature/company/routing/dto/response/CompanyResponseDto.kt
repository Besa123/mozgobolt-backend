package com.mozgobolt.feature.company.routing.dto.response

import com.mozgobolt.feature.company.domain.model.Company
import kotlinx.serialization.Serializable

@Serializable
data class CompanyResponseDto(
    val id: Int,
    val name: String,
    val inviteCode: String,
)

fun Company.toResponseDto() =
    CompanyResponseDto(
        id = id,
        name = name,
        inviteCode = inviteCode,
    )
