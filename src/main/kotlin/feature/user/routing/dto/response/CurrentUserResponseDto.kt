/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.routing.dto.response

import com.mozgobolt.feature.user.domain.model.CurrentUserProfile
import kotlinx.serialization.Serializable

@Serializable
data class CurrentUserResponseDto(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val emailVerified: Boolean,
    val contactInfo: ContactInfoResponseDto,
)

fun CurrentUserProfile.toCurrentUserResponseDto() =
    CurrentUserResponseDto(
        id = id.toString(),
        email = email,
        name = name,
        role = role.name,
        emailVerified = emailVerified,
        contactInfo = contactInfo.toResponseDto(),
    )
