/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.service

import com.mozgobolt.core.data.push.InvalidPushTargetException
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.push.PushNotificationSender
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
        val installations = tx.transactional { deviceInstallationRepository.findAllForUser(userId) }

        coroutineScope {
            val sends =
                installations.map { installation ->
                    async {
                        runSuspendCatching {
                            pushNotificationSender.send(installation.installationId, data)
                        }.onFailure { throwable ->
                            if (throwable is InvalidPushTargetException) {
                                runSuspendCatching {
                                    tx.transactional {
                                        deviceInstallationRepository.deleteIfCurrent(installation)
                                    }
                                }.onFailure { cleanupFailure ->
                                    logger.warn {
                                        "Failed to clean up an invalid push target for user ${installation.userId} " +
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
