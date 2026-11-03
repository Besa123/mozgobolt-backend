/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

class UserSecurityDataRetentionPurgerTest {
    private class CountingTransactionalRunner : TransactionalRunner {
        var callCount = 0
            private set

        override suspend fun <T> transactional(block: suspend () -> T): T {
            callCount++
            return block()
        }
    }

    @Test
    fun `purge runs token and outbox cleanup atomically with bounded retention cutoffs`() =
        kotlinx.coroutines.runBlocking {
            val now = Instant.parse("2026-03-01T00:00:00Z")
            val userRepository = FakeUserRepository().apply { authenticationTokensToPurge = 3 }
            val outboxRepository =
                RecordingUserEmailOutboxRepository().apply {
                    enqueue(
                        userId = 1,
                        type = com.mozgobolt.feature.user.domain.model.UserEmailType.VERIFICATION,
                        tokenHash = "expired-hash",
                        rawToken = "expired-raw",
                        expiresAt = now.minusSeconds(1),
                    )
                    enqueue(
                        userId = 1,
                        type = com.mozgobolt.feature.user.domain.model.UserEmailType.PASSWORD_RESET,
                        tokenHash = "active-hash",
                        rawToken = "active-raw",
                        expiresAt = now.plusSeconds(60),
                    )
                }
            val tx = CountingTransactionalRunner()
            val purger =
                UserSecurityDataRetentionPurger(
                    userRepository = userRepository,
                    emailOutboxRepository = outboxRepository,
                    tx = tx,
                    backgroundScope = ApplicationBackgroundScope(),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            assertEquals(4, purger.purge())
            assertEquals(1, tx.callCount)
            assertEquals(
                listOf(now to now.minus(30.days.toJavaDuration())),
                userRepository.authenticationTokenPurgeCutoffs,
            )
            assertEquals(listOf("active-hash"), outboxRepository.pending.map { it.tokenHash })
        }
}
