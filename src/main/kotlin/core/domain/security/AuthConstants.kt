/*
 * SPDX-License-Identifier: Apache-2.0
 */

// These are JWT claim names and token-type values, not credential material.
@file:Suppress("HardcodedCredentials")

package com.mozgobolt.core.domain.security

/** JWT claim names, token types, and the protected authentication provider name. */
object AuthConstants {
    /** JWT claim containing the user's identifier. */
    const val CLAIM_USER_ID = "userId"

    /** JWT claim containing the user's role. */
    const val CLAIM_USER_ROLE = "role"

    /** JWT claim identifying the token type. */
    const val CLAIM_TOKEN_TYPE = "type"

    /** JWT claim containing the refresh-token family identifier. */
    const val CLAIM_TOKEN_FAMILY_ID = "sid"

    /** Claim value identifying an access token. */
    const val TOKEN_TYPE_ACCESS = "access"

    /** Claim value identifying a refresh token. */
    const val TOKEN_TYPE_REFRESH = "refresh"

    /** Ktor authentication provider used by protected routes. */
    const val PROTECT_ENDPOINT_JWT = "auth-jwt"
}
