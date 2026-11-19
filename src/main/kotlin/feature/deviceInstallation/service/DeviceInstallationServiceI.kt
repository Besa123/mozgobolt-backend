/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.service

import com.mozgobolt.core.data.push.InvalidPushTargetException
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.core.resilience.PUSH_MAX_CONCURRENT_CALLS
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationError
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

private val logger = KotlinLogging.logger {}

class DeviceInstallationServiceI(
    private val deviceInstallationRepository: DeviceInstallationRepository,
    private val tx: TransactionalRunner,
    private val pushNotificationSender: PushNotificationSender,
) : DeviceInstallationService {
    override suspend fun registerInstallation(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
    ): DeviceInstallation =
        tx.transactional {
            deviceInstallationRepository.register(
                userId = userId,
                installationId = installationId,
                platform = platform,
                now = Instant.now(),
            )
        }

    override suspend fun unregisterInstallation(
        userId: Int,
        installationId: String,
    ): AppResult<Unit, DeviceInstallationError> =
        tx.transactional {
            if (deviceInstallationRepository.deleteForUser(userId, installationId)) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(DeviceInstallationError.NOT_FOUND)
            }
        }

    override suspend fun sendPush(
        userId: Int,
        data: Map<String, String>,
    ) {
        installationPages(userId).collect { page -> sendPage(page, data) }
    }

    private fun installationPages(userId: Int): Flow<List<DeviceInstallation>> =
        flow {
            // Capture the watermark and first page together, then close the transaction before push I/O.
            val snapshot =
                tx.transactional {
                    deviceInstallationRepository.findLatestIdForUser(userId)?.let { throughId ->
                        throughId to
                            deviceInstallationRepository.findPageForUser(
                                userId = userId,
                                afterId = null,
                                throughId = throughId,
                                limit = PUSH_MAX_CONCURRENT_CALLS,
                            )
                    }
                } ?: return@flow
            val (throughId, firstPage) = snapshot
            if (firstPage.isEmpty()) return@flow

            var afterId = firstPage.last().id
            emit(firstPage)
            while (afterId < throughId) {
                val page =
                    tx.transactional {
                        deviceInstallationRepository.findPageForUser(
                            userId = userId,
                            afterId = afterId,
                            throughId = throughId,
                            limit = PUSH_MAX_CONCURRENT_CALLS,
                        )
                    }
                if (page.isEmpty()) return@flow
                afterId = page.last().id
                emit(page)
            }
        }

    private suspend fun sendPage(
        page: List<DeviceInstallation>,
        data: Map<String, String>,
    ) {
        coroutineScope {
            val sends =
                page.map { installation ->
                    async {
                        runSuspendCatching {
                            pushNotificationSender.send(installation.installationId, data)
                        }.onError { throwable ->
                            if (throwable is InvalidPushTargetException) {
                                runSuspendCatching {
                                    tx.transactional {
                                        deviceInstallationRepository.deleteIfCurrent(installation)
                                    }
                                }.onError { cleanupFailure ->
                                    logger.warn {
                                        "Failed to clean up invalid push target for user ${installation.userId} " +
                                            "(${cleanupFailure.javaClass.simpleName})"
                                    }
                                }
                            }
                        }
                    }
                }
            sends.awaitAll()
        }
    }
}
