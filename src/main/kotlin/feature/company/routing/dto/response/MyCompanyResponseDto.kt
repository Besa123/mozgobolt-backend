/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.routing.dto.response

import com.mozgobolt.feature.company.domain.model.CompanyMembershipSummary
import kotlinx.serialization.Serializable

@Serializable
data class MyCompanyResponseDto(
    val id: Int,
    val name: String,
    val membershipRole: String,
)

fun CompanyMembershipSummary.toMyCompanyResponseDto() =
    MyCompanyResponseDto(
        id = companyId,
        name = companyName,
        membershipRole = membershipRole.name,
    )
