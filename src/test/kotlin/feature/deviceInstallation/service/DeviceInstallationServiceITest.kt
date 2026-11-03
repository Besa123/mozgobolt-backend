/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.service

import com.mozgobolt.core.data.push.FakePushNotificationSender
import com.mozgobolt.core.data.push.InvalidPushTargetException
import com.mozgobolt.core.data.push.TransientPushDeliveryException
import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.feature.deviceInstallation.domain.FakeDeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationError
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DeviceInstallationServiceITest {
    private class Fixture {
        val repository = FakeDeviceInstallationRepository()
        val pushSender = FakePushNotificationSender()
        val service = DeviceInstallationServiceI(repository, NoopTransactionalRunner(), pushSender)
    }

    @Test
    fun `registering a new installation creates a row owned by that user`() {
        runBlocking {
            val fx = Fixture()

            val registered =
                fx.service.registerInstallation(
                    userId = 1,
                    installationId = "device-a",
                    platform = DevicePlatform.ANDROID,
                )

            assertEquals(1, registered.userId)
            assertEquals("device-a", registered.installationId)
            assertEquals(DevicePlatform.ANDROID, registered.platform)
            assertEquals(1L, registered.registrationVersion)
            assertEquals(1, fx.repository.findAllForUser(1).size)
        }
    }

    @Test
    fun `re-registering the same installation id for the same user does not create a duplicate row`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "device-a", platform = DevicePlatform.ANDROID)

            fx.service.registerInstallation(userId = 1, installationId = "device-a", platform = DevicePlatform.ANDROID)

            assertEquals(1, fx.repository.findAllForUser(1).size, "re-registering must be a no-op, not a duplicate")
        }
    }

    @Test
    fun `registering a second, different installation id for the same user keeps both`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "device-a", platform = DevicePlatform.ANDROID)

            fx.service.registerInstallation(userId = 1, installationId = "device-b", platform = DevicePlatform.ANDROID)

            assertEquals(2, fx.repository.findAllForUser(1).size)
        }
    }

    @Test
    fun `re-registering an existing installation id under a different platform updates the platform`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "device-a", platform = DevicePlatform.ANDROID)

            val updated =
                fx.service.registerInstallation(userId = 1, installationId = "device-a", platform = DevicePlatform.IOS)

            assertEquals(DevicePlatform.IOS, updated.platform)
            assertEquals(2L, updated.registrationVersion)
            assertEquals(1, fx.repository.findAllForUser(1).size)
        }
    }

    @Test
    fun `registering an installation for a different user transfers ownership`() {
        runBlocking {
            val fx = Fixture()
            val original =
                fx.service.registerInstallation(
                    userId = 1,
                    installationId = "shared-device",
                    platform = DevicePlatform.ANDROID,
                )

            val transferred =
                fx.service.registerInstallation(
                    userId = 2,
                    installationId = "shared-device",
                    platform = DevicePlatform.IOS,
                )
            val oldOwnerUnregister =
                fx.service.unregisterInstallation(userId = 1, installationId = "shared-device")

            assertEquals(original.id, transferred.id)
            assertEquals(2, transferred.userId)
            assertEquals(original.registrationVersion + 1, transferred.registrationVersion)
            assertTrue(fx.repository.findAllForUser(1).isEmpty())
            assertEquals(listOf(transferred), fx.repository.findAllForUser(2))
            oldOwnerUnregister.fold(
                onSuccess = { fail("the former owner must not be able to unregister the transferred installation") },
                onError = { assertEquals(DeviceInstallationError.NOT_FOUND, it) },
            )
            assertEquals(1, fx.repository.findAllForUser(2).size)
        }
    }

    @Test
    fun `unregistering an owned installation succeeds`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "device-a", platform = DevicePlatform.ANDROID)

            val result = fx.service.unregisterInstallation(userId = 1, installationId = "device-a")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(0, fx.repository.findAllForUser(1).size)
        }
    }

    @Test
    fun `unregistering an installation id that does not exist is rejected as not found`() {
        runBlocking {
            val fx = Fixture()

            val result = fx.service.unregisterInstallation(userId = 1, installationId = "does-not-exist")

            result.fold(onSuccess = { fail("expected NOT_FOUND but got success") }, onError = {
                assertEquals(DeviceInstallationError.NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `unregistering someone else's installation is rejected as not found, not forbidden`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "device-a", platform = DevicePlatform.ANDROID)

            val result = fx.service.unregisterInstallation(userId = 2, installationId = "device-a")

            result.fold(onSuccess = { fail("expected NOT_FOUND but got success") }, onError = {
                assertEquals(DeviceInstallationError.NOT_FOUND, it)
            })
            assertEquals(
                1,
                fx.repository.findAllForUser(1).size,
                "the owner's installation must survive someone else's attempt",
            )
        }
    }

    @Test
    fun `sendPush delivers to every installation independently, even when one fails`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "good-a", platform = DevicePlatform.ANDROID)
            fx.service.registerInstallation(userId = 1, installationId = "bad", platform = DevicePlatform.ANDROID)
            fx.service.registerInstallation(userId = 1, installationId = "good-b", platform = DevicePlatform.ANDROID)
            fx.pushSender.failNextSendTo("bad", InvalidPushTargetException("token gone"))

            fx.service.sendPush(userId = 1, data = mapOf("k" to "v"))

            assertEquals(
                setOf("good-a", "good-b"),
                fx.pushSender.sent
                    .map { it.installationId }
                    .toSet(),
                "both healthy installations must still receive the push despite the sibling failure",
            )
        }
    }

    @Test
    fun `sendPush keeps transiently unreachable installations and still delivers to healthy devices`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "healthy", platform = DevicePlatform.ANDROID)
            fx.service.registerInstallation(userId = 1, installationId = "offline", platform = DevicePlatform.IOS)
            fx.pushSender.failNextSendTo("offline", TransientPushDeliveryException("network unavailable"))

            fx.service.sendPush(userId = 1, data = mapOf("k" to "v"))

            assertEquals(listOf("healthy"), fx.pushSender.sent.map { it.installationId })
            assertEquals(
                setOf("healthy", "offline"),
                fx.repository
                    .findAllForUser(1)
                    .map { it.installationId }
                    .toSet(),
                "transient failures must not trigger stale-installation cleanup",
            )
        }
    }

    @Test
    fun `sendPush self-heals an installation FCM reports as invalid, leaving the others untouched`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(userId = 1, installationId = "good", platform = DevicePlatform.ANDROID)
            fx.service.registerInstallation(userId = 1, installationId = "stale", platform = DevicePlatform.ANDROID)
            fx.pushSender.failNextSendTo("stale", InvalidPushTargetException("token gone"))

            fx.service.sendPush(userId = 1, data = mapOf("k" to "v"))

            val remaining = fx.repository.findAllForUser(1).map { it.installationId }
            assertEquals(listOf("good"), remaining, "the stale installation should have deleted itself")
            assertTrue(fx.pushSender.sent.any { it.installationId == "good" })
        }
    }

    @Test
    fun `sendPush only delivers to the current owner after an installation is transferred`() {
        runBlocking {
            val fx = Fixture()
            fx.service.registerInstallation(
                userId = 1,
                installationId = "shared-device",
                platform = DevicePlatform.ANDROID,
            )
            fx.service.registerInstallation(userId = 2, installationId = "shared-device", platform = DevicePlatform.IOS)

            fx.service.sendPush(userId = 1, data = mapOf("k" to "v"))

            assertTrue(fx.pushSender.sent.isEmpty(), "the former owner must not receive pushes on this device")

            fx.service.sendPush(userId = 2, data = mapOf("k" to "v"))

            assertEquals(listOf("shared-device"), fx.pushSender.sent.map { it.installationId })
        }
    }

    @Test
    fun `an invalid response from a stale send cannot remove an installation transferred in flight`() {
        runBlocking {
            val repository = FakeDeviceInstallationRepository()
            val registrationService =
                DeviceInstallationServiceI(repository, NoopTransactionalRunner(), FakePushNotificationSender())
            val sender =
                object : PushNotificationSender {
                    override suspend fun send(
                        installationId: String,
                        data: Map<String, String>,
                    ) {
                        registrationService.registerInstallation(
                            userId = 2,
                            installationId = installationId,
                            platform = DevicePlatform.IOS,
                        )
                        throw InvalidPushTargetException("old registration is no longer valid")
                    }
                }
            val pushService = DeviceInstallationServiceI(repository, NoopTransactionalRunner(), sender)
            val original =
                pushService.registerInstallation(
                    userId = 1,
                    installationId = "transferred-during-send",
                    platform = DevicePlatform.ANDROID,
                )

            pushService.sendPush(userId = 1, data = mapOf("type" to "test"))

            val current = repository.findAllForUser(2).single()
            assertEquals(DevicePlatform.IOS, current.platform)
            assertEquals(original.registrationVersion + 1, current.registrationVersion)
            assertTrue(repository.findAllForUser(1).isEmpty())
        }
    }

    @Test
    fun `sendPush with no installations registered is a safe no-op`() {
        runBlocking {
            val fx = Fixture()

            fx.service.sendPush(userId = 1, data = mapOf("k" to "v"))

            assertTrue(fx.pushSender.sent.isEmpty())
        }
    }
}
