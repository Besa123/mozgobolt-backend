/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

import java.time.Instant

enum class UserEmailType {
    VERIFICATION,
    PASSWORD_RESET,
}

data class UserEmailOutboxDelivery(
    val id: Long,
    val userId: Int,
    val recipientEmail: String,
    val type: UserEmailType,
    val tokenHash: String,
    val encryptedToken: String,
    val expiresAt: Instant,
    val leaseToken: String,
    val attemptCount: Int,
)

internal fun userEmailOutboxEncryptionContext(
    userId: Int,
    type: UserEmailType,
    tokenHash: String,
): String = "user-email-outbox-v1:$userId:${type.name}:$tokenHash"
