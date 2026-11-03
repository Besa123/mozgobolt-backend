/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.domain.model

import java.time.Duration
import java.time.Instant

/**
 * Progressive lockout policy.
 *
 * Lockout duration increases with each failed attempt beyond the threshold:
 * - 5 failures → 1 minute lock
 * - 6 failures → 2 minutes
 * - 7 failures → 4 minutes
 * - 8 failures → 8 minutes
 * - 9 failures → 16 minutes
 * - 10+ failures → 30 minutes (capped)
 *
 * Resets to 0 on successful login.
 */
object LockoutPolicy {
    /** Number of consecutive failed attempts permitted before lockout begins. */
    const val MAX_ATTEMPTS_BEFORE_LOCK = 5
    private const val MAX_EXPONENT = 10
    private val MAX_LOCKOUT_DURATION: Duration = Duration.ofMinutes(30)
    private val BASE_LOCKOUT_DURATION: Duration = Duration.ofMinutes(1)

    /** Returns the progressive lockout expiry for [failedAttempts], or `null` before the threshold. */
    fun calculateLockUntil(failedAttempts: Int): Instant? {
        val attemptsOverThreshold = failedAttempts.coerceAtLeast(0) - MAX_ATTEMPTS_BEFORE_LOCK + 1

        return attemptsOverThreshold.takeIf { it > 0 }?.let { over ->
            val multiplier = 1L shl (over - 1).coerceAtMost(MAX_EXPONENT)
            val lockDuration = BASE_LOCKOUT_DURATION.multipliedBy(multiplier).coerceAtMost(MAX_LOCKOUT_DURATION)
            Instant.now().plus(lockDuration)
        }
    }
}
