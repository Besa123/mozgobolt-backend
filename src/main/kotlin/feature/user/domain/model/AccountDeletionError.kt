/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

enum class AccountDeletionError {
    USER_NOT_FOUND,
    INVALID_CREDENTIALS,
    LAST_COMPANY_ADMIN,
}
