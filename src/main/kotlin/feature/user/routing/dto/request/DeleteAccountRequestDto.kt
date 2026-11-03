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
data class DeleteAccountRequestDto(
    val currentPassword: String,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (currentPassword.isEmpty()) add("Current password is required")
            if (currentPassword.length > PasswordPolicy.MAX_LENGTH) add("Current password is too long")
        }
}
