package com.mozgobolt.feature.company.domain.model

import java.time.Instant

data class Company(
    val id: Int,
    val name: String,
    val inviteCode: String,
    val createdAt: Instant,
)
