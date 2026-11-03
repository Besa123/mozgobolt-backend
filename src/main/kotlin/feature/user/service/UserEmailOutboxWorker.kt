/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.data.email.PermanentEmailDeliveryException
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.user.domain.EmailTokenCipher
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.model.UserEmailOutboxDelivery
import com.mozgobolt.feature.user.domain.model.UserEmailType
import com.mozgobolt.feature.user.domain.model.userEmailOutboxEncryptionContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

class UserEmailOutboxWorker(
    private val repository: UserEmailOutboxRepository,
    private val tokenCipher: EmailTokenCipher,
    private val emailService: EmailService,
    private val tx: TransactionalRunner,
    private val backgroundScope: ApplicationBackgroundScope,
    private val clock: Clock = Clock.systemUTC(),
    private val pollInterval: Duration = 2.seconds,
    private val leaseDuration: Duration = 5.minutes,
    private val batchSize: Int = 20,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job =
            backgroundScope.launch(CoroutineName("user-email-outbox-worker")) {
                while (isActive) {
                    runSuspendCatching { processAvailableOnce() }
                        .onFailure { failure ->
                            logger.error {
                                "Email outbox polling failed (${failure::class.simpleName}); retrying next poll"
                            }
                        }
                    delay(pollInterval)
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun processAvailableOnce() {
        val now = clock.instant()
        val leaseUntil = now.plus(leaseDuration.toJavaDuration())
        val deliveries =
            tx.transactional {
                repository.claimBatch(now = now, leaseUntil = leaseUntil, limit = batchSize)
            }

        coroutineScope {
            deliveries
                .map { delivery -> async { deliver(delivery) } }
                .awaitAll()
        }
    }

    private suspend fun deliver(delivery: UserEmailOutboxDelivery) {
        val now = clock.instant()
        val isCurrent =
            tx.transactional {
                repository.isCurrent(delivery.id, delivery.leaseToken, now)
            }
        if (!isCurrent) {
            tx.transactional { repository.discard(delivery.id, delivery.leaseToken) }
            return
        }

        val token =
            try {
                tokenCipher.decrypt(
                    ciphertext = delivery.encryptedToken,
                    context =
                        userEmailOutboxEncryptionContext(
                            delivery.userId,
                            delivery.type,
                            delivery.tokenHash,
                        ),
                )
            } catch (failure: GeneralSecurityException) {
                failPermanently(delivery, failure)
                null
            } catch (failure: IllegalArgumentException) {
                failPermanently(delivery, failure)
                null
            } ?: return

        val failure =
            runSuspendCatching {
                when (delivery.type) {
                    UserEmailType.VERIFICATION -> emailService.sendVerificationEmail(delivery.recipientEmail, token)
                    UserEmailType.PASSWORD_RESET -> emailService.sendPasswordResetEmail(delivery.recipientEmail, token)
                }
            }.exceptionOrNull()

        if (failure == null) {
            val wasMarkedDelivered = tx.transactional { repository.markDelivered(delivery.id, delivery.leaseToken) }
            if (!wasMarkedDelivered) {
                logger.warn {
                    "Email outbox lease was lost after delivery " +
                        "(messageId=${delivery.id}, userId=${delivery.userId}, type=${delivery.type})"
                }
            } else {
                logger.info {
                    "Email outbox delivery succeeded " +
                        "(messageId=${delivery.id}, userId=${delivery.userId}, type=${delivery.type})"
                }
            }
            return
        }

        if (failure is CancellationException) throw failure
        if (failure !is Exception) throw failure

        if (failure is PermanentEmailDeliveryException) {
            failPermanently(delivery, failure)
        } else {
            scheduleRetry(delivery, failure)
        }
    }

    private suspend fun scheduleRetry(
        delivery: UserEmailOutboxDelivery,
        failure: Throwable,
    ) {
        val nextAttemptAt = clock.instant().plusSeconds(retryDelaySeconds(delivery.attemptCount))
        val wasScheduled =
            if (nextAttemptAt.isAfter(delivery.expiresAt)) {
                tx.transactional { repository.discard(delivery.id, delivery.leaseToken) }
            } else {
                tx.transactional {
                    repository.scheduleRetry(
                        id = delivery.id,
                        leaseToken = delivery.leaseToken,
                        nextAttemptAt = nextAttemptAt,
                        failureType = failure.javaClass.simpleName.take(MAX_FAILURE_TYPE_LENGTH),
                    )
                }
            }

        if (wasScheduled) {
            logger.warn {
                "Email outbox delivery will retry " +
                    "(messageId=${delivery.id}, userId=${delivery.userId}, " +
                    "type=${delivery.type}, failure=${failure.javaClass.simpleName})"
            }
        }
    }

    private suspend fun failPermanently(
        delivery: UserEmailOutboxDelivery,
        failure: Throwable,
    ) {
        val wasMarkedFailed =
            tx.transactional {
                repository.markFailed(
                    id = delivery.id,
                    leaseToken = delivery.leaseToken,
                    failedAt = clock.instant(),
                    failureType = failure.javaClass.simpleName.take(MAX_FAILURE_TYPE_LENGTH),
                )
            }

        if (wasMarkedFailed) {
            logger.error {
                "Email outbox delivery failed permanently " +
                    "(messageId=${delivery.id}, userId=${delivery.userId}, " +
                    "type=${delivery.type}, failure=${failure.javaClass.simpleName})"
            }
        }
    }

    private fun retryDelaySeconds(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, MAX_RETRY_EXPONENT)
        return (1L shl exponent).coerceAtMost(MAX_RETRY_DELAY_SECONDS)
    }

    private companion object {
        const val MAX_FAILURE_TYPE_LENGTH = 100
        const val MAX_RETRY_EXPONENT = 9
        const val MAX_RETRY_DELAY_SECONDS = 300L
    }
}
