package com.mozgobolt.feature.company.data.mapper

import com.mozgobolt.feature.company.data.database.CompanyMembershipEntity
import com.mozgobolt.feature.company.domain.model.CompanyMembership

fun CompanyMembershipEntity.toCompanyMembership() =
    CompanyMembership(
        id = id.value,
        companyId = companyId,
        userId = userId,
        role = role,
        joinedAt = joinedAt,
    )
