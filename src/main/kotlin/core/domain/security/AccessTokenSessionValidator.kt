/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.security

/** Checks whether an access token's user and session family are still active. */
fun interface AccessTokenSessionValidator {
    /** Reports whether [familyId] is active for [userId]. */
    suspend fun isActive(
        userId: Int,
        familyId: String,
    ): Boolean
}
