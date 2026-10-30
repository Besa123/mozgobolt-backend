package com.mozgobolt.feature.company.domain.model

data class CompanyMemberSummary(
    val userId: Int,
    val name: String,
    val role: CompanyRole,
)
