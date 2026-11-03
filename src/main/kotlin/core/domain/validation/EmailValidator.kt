/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.validation

/** Normalizes email addresses and checks whether they meet the accepted format. */
interface EmailValidator {
    /** Returns the canonical form used for account lookup and uniqueness. */
    fun normalize(email: String): String

    /** Reports whether [email] satisfies the accepted address format. */
    fun isValid(email: String): Boolean
}
