/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}
private val DEFAULT_PURGE_CHECK_INTERVAL = 1.hours
internal val ONE_TIME_TOKEN_RETENTION_PERIOD = 30.days

class UserSecurityDataRetentionPurger(
    private val userRepository: UserRepository,
    private val emailOutboxRepository: UserEmailOutboxRepository,
    private val tx: TransactionalRunner,
    private val backgroundScope: ApplicationBackgroundScope,
    private val clock: Clock = Clock.systemUTC(),
    private val checkInterval: Duration = DEFAULT_PURGE_CHECK_INTERVAL,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job =
            backgroundScope.launch(CoroutineName("user-security-data-retention-purger")) {
                while (isActive) {
                    runSuspendCatching { purge() }
                        .onFailure {
                            logger.error(it) {
                                "Failed to purge expired authentication data; retrying next interval"
                            }
                        }
                    delay(checkInterval)
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun purge(): Int {
        val now = clock.instant()
        val oneTimeTokenRetentionCutoff = now.minus(ONE_TIME_TOKEN_RETENTION_PERIOD.toJavaDuration())
        return tx.transactional {
            userRepository.purgeExpiredAuthenticationTokens(now, oneTimeTokenRetentionCutoff) +
                emailOutboxRepository.purgeExpired(now)
        }
    }
}
