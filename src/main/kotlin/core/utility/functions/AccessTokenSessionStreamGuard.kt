/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import com.mozgobolt.core.domain.security.AccessTokenSessionValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Duration

private val DEFAULT_SESSION_RECHECK_INTERVAL = Duration.ofSeconds(30)

/** Collects [action] until the access token's session family is revoked or the flow completes. */
suspend fun <T> Flow<T>.collectWhileAccessSessionActive(
    userId: Int,
    familyId: String,
    validator: AccessTokenSessionValidator,
    recheckInterval: Duration = DEFAULT_SESSION_RECHECK_INTERVAL,
    action: suspend (T) -> Unit,
) {
    coroutineScope {
        if (validator.isActive(userId, familyId)) {
            val collector: Job = launch { collect(action) }
            while (collector.isActive) {
                delay(recheckInterval.toMillis())
                if (!validator.isActive(userId, familyId)) {
                    collector.cancelAndJoin()
                    break
                }
            }
            collector.join()
        }
    }
}
