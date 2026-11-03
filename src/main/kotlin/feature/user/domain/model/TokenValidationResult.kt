/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

import java.time.Instant

sealed interface TokenValidationResult {
    data class Valid(
        val familyId: String,
        val familyExpiresAt: Instant,
    ) : TokenValidationResult

    data class AlreadyRevoked(
        val familyId: String,
    ) : TokenValidationResult

    data object NotFound : TokenValidationResult
}
