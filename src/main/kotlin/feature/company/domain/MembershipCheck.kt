/*
 * SPDX-License-Identifier: Apache-2.0
 */

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
    /** The user is an administrator; [membership] contains the resolved membership. */
    data class Admin(
        /** The membership that satisfied the administrator check. */
        val membership: CompanyMembership,
    ) : MembershipCheck

    /** The user has no membership in the company. */
    data object NotAMember : MembershipCheck

    /** The user is a member but does not have the administrator role. */
    data object NotAdmin : MembershipCheck
}

/** Resolves whether [userId] has an administrator membership in [companyId]. */
suspend fun CompanyMembershipRepository.requireAdminMembership(
    companyId: Int,
    userId: Int,
): MembershipCheck {
    val membership = find(companyId, userId) ?: return MembershipCheck.NotAMember
    return if (membership.role == CompanyRole.ADMIN) MembershipCheck.Admin(membership) else MembershipCheck.NotAdmin
}
