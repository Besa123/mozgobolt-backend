package com.mozgobolt.feature.user.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import kotlinx.serialization.Serializable

@Serializable
data class LogoutRequestDto(
    val refreshToken: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (refreshToken.isBlank()) add("Refresh token is required")
        }
}
