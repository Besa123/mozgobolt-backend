package com.mozgobolt.feature.company.domain.model

import java.time.Instant

data class CompanyMembership(
    val id: Int,
    val companyId: Int,
    val userId: Int,
    val role: CompanyRole,
    val joinedAt: Instant,
)
