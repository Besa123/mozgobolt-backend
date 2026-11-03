/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.domain.model

data class CompanyMembershipSummary(
    val companyId: Int,
    val companyName: String,
    val membershipRole: CompanyRole,
)
