package com.shelflife.feature.user.routing.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class MessageResponseDto(
    val message: String,
)
