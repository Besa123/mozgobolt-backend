/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.data.mapper

import com.mozgobolt.feature.company.data.database.CompaniesTable
import com.mozgobolt.feature.company.data.database.CompanyEntity
import com.mozgobolt.feature.company.domain.model.Company
import org.jetbrains.exposed.v1.core.ResultRow

fun CompanyEntity.toCompany() =
    Company(
        id = id.value,
        name = name,
        inviteCode = inviteCode,
        createdAt = createdAt,
    )

fun ResultRow.toCompany() =
    Company(
        id = this[CompaniesTable.id].value,
        name = this[CompaniesTable.name],
        inviteCode = this[CompaniesTable.inviteCode],
        createdAt = this[CompaniesTable.createdAt],
    )
