/*
 * SPDX-License-Identifier: Apache-2.0
 */

// Credential fields are required for inbound request deserialization, not API response serialization.
@file:Suppress("KotlinxSerializationSensitiveField")

package com.mozgobolt.feature.user.routing.dto.request

import com.mozgobolt.core.domain.security.PasswordPolicy
import com.mozgobolt.core.domain.validation.ValidatedRequest
import kotlinx.serialization.Serializable

@Serializable
data class PasswordResetRequestDto(
    val email: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (email.isBlank()) add("Email is required")
        }
}

@Serializable
data class PasswordResetValidateRequestDto(
    val token: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (token.isBlank()) add("Token is required")
        }
}

@Serializable
data class PasswordResetConfirmRequestDto(
    val token: String,
    val newPassword: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (token.isBlank()) add("Token is required")
            if (newPassword.isBlank()) {
                add("New password is required")
            } else if (!PasswordPolicy.isValid(newPassword)) {
                add("New password must be 15-128 characters and not commonly used")
            }
        }
}
