/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.feature.user.domain.model.AccountDeletionError

/** Account session and lifecycle operations. */
interface UserSessionService {
    /** Revokes one refresh-token family belonging to [userId]. */
    suspend fun logoutUser(
        userId: Int,
        familyId: String,
    )

    /** Revokes every refresh-token family belonging to [userId]. */
    suspend fun logoutAllSessions(userId: Int)

    /** Deletes the account after verifying [currentPassword]. */
    suspend fun deleteAccount(
        userId: Int,
        currentPassword: String,
    ): AppResult<Unit, AccountDeletionError>
}
