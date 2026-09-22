package com.mozgobolt.feature.company.data.mapper

import com.mozgobolt.feature.company.data.database.CompanyEntity
import com.mozgobolt.feature.company.domain.model.Company

fun CompanyEntity.toCompany() =
    Company(
        id = id.value,
        name = name,
        inviteCode = inviteCode,
        createdAt = createdAt,
    )
