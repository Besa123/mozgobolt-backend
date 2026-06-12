package com.besa.boardShare.core.routing.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String
)