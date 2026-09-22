package com.mozgobolt.feature.company.domain.model

/**
 * The only two membership tiers this app has — confirmed decision, no separate "owner" role above
 * admin. Any [ADMIN] can delete/modify the company, add vehicles, end anyone's active session, and
 * promote/demote other members. A [MEMBER] can only start/end their own session.
 */
enum class CompanyRole {
    ADMIN,
    MEMBER,
}
