package com.mozgobolt.core.routing.dto.response

import kotlinx.serialization.Serializable

/**
 * Unified envelope for all API error responses: [error] is the machine-readable code
 * clients switch on; [message] and [details] are human-readable context only.
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val message: String = "",
    val details: List<String> = emptyList(),
)
