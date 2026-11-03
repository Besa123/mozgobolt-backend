/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.security

import java.time.Instant

/** Generates, verifies, and hashes authentication tokens. */
interface TokenManager {
    /** Creates an access token for a user, role, and refresh-token family. */
    fun generateAccessToken(
        userId: Int,
        role: String,
        familyId: String,
    ): String

    /** Creates a refresh token with an optional family expiration time. */
    fun generateRefreshToken(
        userId: Int,
        familyExpiresAt: Instant? = null,
    ): String

    /** Verifies a refresh token and returns its user identifier, or `null` when invalid. */
    fun verifyAndGetUserIdFromRefreshToken(token: String): Int?

    /** Produces the one-way digest used to persist a token safely. */
    fun hashTokenForStorage(token: String): String
}
