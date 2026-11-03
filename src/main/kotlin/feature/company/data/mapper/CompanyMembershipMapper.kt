/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.data.mapper

import com.mozgobolt.feature.company.data.database.CompanyMembershipEntity
import com.mozgobolt.feature.company.data.database.CompanyMembershipsTable
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import org.jetbrains.exposed.v1.core.ResultRow

fun CompanyMembershipEntity.toCompanyMembership() =
    CompanyMembership(
        id = id.value,
        companyId = companyId,
        userId = userId,
        role = role,
        joinedAt = joinedAt,
    )

fun ResultRow.toCompanyMembership() =
    CompanyMembership(
        id = this[CompanyMembershipsTable.id].value,
        companyId = this[CompanyMembershipsTable.companyId],
        userId = this[CompanyMembershipsTable.userId],
        role = this[CompanyMembershipsTable.role],
        joinedAt = this[CompanyMembershipsTable.joinedAt],
    )
