package com.mozgobolt.feature.company.domain.model

/**
 * Single source of truth for the invite code's shape — shared between
 * [com.mozgobolt.feature.company.service.CompanyServiceI]'s generation and
 * [com.mozgobolt.feature.company.data.database.CompaniesTable]'s column width, so the two can
 * never quietly drift out of sync (a generator change that outgrows the column would otherwise
 * only surface as an insert failure, not a compile error).
 */
object CompanyConstraints {
    /** [com.mozgobolt.core.domain.security.SecureTokenGenerator.generate] base64url-encodes this
     * many random bytes, with no padding. */
    const val INVITE_CODE_BYTE_LENGTH = 6

    /** Base64 (no padding) emits 4 output characters per 3 input bytes, rounded up —
     * `ceil(a / b)` as integer arithmetic is `(a + b - 1) / b`. */
    const val INVITE_CODE_LENGTH = (INVITE_CODE_BYTE_LENGTH * 4 + 2) / 3

    /** Longest [CompanyRole] name is "MEMBER" (6 chars) — rounded up with headroom the same way
     * [com.mozgobolt.feature.user.domain.model.UserRole]'s column width already is. */
    const val ROLE_COLUMN_LENGTH = 10
}
