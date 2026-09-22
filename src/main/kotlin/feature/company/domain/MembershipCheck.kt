package com.mozgobolt.feature.company.domain

import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.company.domain.model.CompanyRole

/**
 * Result of resolving whether a user is an admin member of a company — the one place this
 * two-step "look up the membership, then check its role" domain rule lives. Every feature that
 * needs an admin-only check maps this to its own error type in a single `when`, instead of
 * re-deriving the same lookup-and-role-check by hand at each call site.
 */
sealed interface MembershipCheck {
    data class Admin(
        val membership: CompanyMembership,
    ) : MembershipCheck

    data object NotAMember : MembershipCheck

    data object NotAdmin : MembershipCheck
}

suspend fun CompanyMembershipRepository.requireAdminMembership(
    companyId: Int,
    userId: Int,
): MembershipCheck {
    val membership = find(companyId, userId) ?: return MembershipCheck.NotAMember
    return if (membership.role == CompanyRole.ADMIN) MembershipCheck.Admin(membership) else MembershipCheck.NotAdmin
}
