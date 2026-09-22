package com.mozgobolt.feature.company.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import kotlinx.serialization.Serializable

@Serializable
data class JoinCompanyRequestDto(
    val inviteCode: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (inviteCode.isBlank()) add("Invite code is required")
        }
}
