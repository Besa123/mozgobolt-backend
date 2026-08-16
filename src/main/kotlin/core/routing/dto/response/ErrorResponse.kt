package com.besa.boardShare.core.routing.dto.response

import kotlinx.serialization.Serializable

/**
 * Unified error envelope for all API error responses.
 *
 * ```json
 * {
 *   "error": "VALIDATION_FAILED",
 *   "message": "Request validation failed",
 *   "details": ["email: must not be blank"]
 * }
 * ```
 *
 * - [error]: Machine-readable code (frontend switches on this)
 * - [message]: Human-readable explanation (for debugging/display)
 * - [details]: Optional list of specific issues (field errors, context)
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val message: String = "",
    val details: List<String> = emptyList(),
)