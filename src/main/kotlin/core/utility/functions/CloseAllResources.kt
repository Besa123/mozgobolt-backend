/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

/** Attempts every cleanup action, then rethrows the first failure with later failures suppressed. */
@Suppress("TooGenericExceptionCaught")
internal fun closeAllResources(vararg cleanupActions: () -> Unit) {
    var firstFailure: Throwable? = null
    cleanupActions.forEach { cleanup ->
        try {
            cleanup()
        } catch (failure: Throwable) {
            firstFailure =
                firstFailure?.also { primaryFailure ->
                    if (primaryFailure !== failure) primaryFailure.addSuppressed(failure)
                } ?: failure
        }
    }
    firstFailure?.let { throw it }
}
