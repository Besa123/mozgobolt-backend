package com.mozgobolt.feature.user.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOLERANCE_MILLIS = 2_000L

class LockoutPolicyTest {
    @Test
    fun `does not lock below the failure threshold`() {
        for (attempts in 0 until LockoutPolicy.MAX_ATTEMPTS_BEFORE_LOCK) {
            assertNull(LockoutPolicy.calculateLockUntil(attempts), "attempts=$attempts should not lock yet")
        }
    }

    @Test
    fun `locks for one minute on the first attempt over the threshold`() {
        assertLockDurationAround(attempts = LockoutPolicy.MAX_ATTEMPTS_BEFORE_LOCK, expectedMinutes = 1)
    }

    @Test
    fun `doubles the lockout duration for each additional failure`() {
        assertLockDurationAround(attempts = LockoutPolicy.MAX_ATTEMPTS_BEFORE_LOCK + 1, expectedMinutes = 2)
        assertLockDurationAround(attempts = LockoutPolicy.MAX_ATTEMPTS_BEFORE_LOCK + 2, expectedMinutes = 4)
        assertLockDurationAround(attempts = LockoutPolicy.MAX_ATTEMPTS_BEFORE_LOCK + 3, expectedMinutes = 8)
    }

    @Test
    fun `caps the lockout duration at thirty minutes`() {
        assertLockDurationAround(attempts = 10, expectedMinutes = 30)
        assertLockDurationAround(attempts = 100, expectedMinutes = 30)
        assertLockDurationAround(attempts = Int.MAX_VALUE, expectedMinutes = 30)
    }

    @Test
    fun `a negative failure count never locks and does not throw`() {
        assertNull(LockoutPolicy.calculateLockUntil(-1))
        assertNull(LockoutPolicy.calculateLockUntil(Int.MIN_VALUE))
    }

    @Test
    fun `zero failures never locks`() {
        assertNull(LockoutPolicy.calculateLockUntil(0))
    }

    private fun assertLockDurationAround(
        attempts: Int,
        expectedMinutes: Long,
    ) {
        val before = Instant.now()
        val lockUntil = LockoutPolicy.calculateLockUntil(attempts)
        checkNotNull(lockUntil) { "attempts=$attempts should lock the account" }

        val expected = before.plusSeconds(expectedMinutes * 60)
        val deltaMillis = kotlin.math.abs(lockUntil.toEpochMilli() - expected.toEpochMilli())
        assertTrue(
            deltaMillis < TOLERANCE_MILLIS,
            "attempts=$attempts expected lock around $expected but got $lockUntil",
        )
    }
}
