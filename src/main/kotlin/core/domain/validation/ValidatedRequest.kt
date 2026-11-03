/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.validation

/** Request body contract for DTOs that validate themselves before a route handler runs. */
interface ValidatedRequest {
    /** Returns validation messages, or an empty list when the request is valid. */
    fun validate(): List<String>
}
