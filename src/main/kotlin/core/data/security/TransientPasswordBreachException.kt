/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.security

internal class TransientPasswordBreachException(
    cause: Throwable? = null,
) : RuntimeException(cause)
