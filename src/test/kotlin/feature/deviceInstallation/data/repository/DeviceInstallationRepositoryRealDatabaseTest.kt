package com.mozgobolt.feature.deviceInstallation.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the real `UNIQUE(user_id, installation_id)` index on `device_installations` actually
 * fires under genuine Postgres concurrency, backing [DeviceInstallationRepositoryI.register]'s
 * insertIgnore-then-update behavior — exactly the class of bug invisible to fakes, only caught by
 * a real DB.
 */
class DeviceInstallationRepositoryRealDatabaseTest {
    @Test
    fun `two concurrent registrations of the same installation id for the same user only ever leave one row`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val now = Instant.now()

            coroutineScope {
                val first =
                    async { tx.transactional { repository.register(1, "device-a", DevicePlatform.ANDROID, now) } }
                val second =
                    async { tx.transactional { repository.register(1, "device-a", DevicePlatform.ANDROID, now) } }
                listOf(first.await(), second.await())
            }

            val allInstallations = tx.transactional { repository.findAllForUser(1) }
            assertEquals(1, allInstallations.size, "only one row must actually exist, regardless of which call won")
        }
    }

    @Test
    fun `registering the same installation id for two different users keeps both rows`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val now = Instant.now()

            tx.transactional { repository.register(1, "shared-device", DevicePlatform.ANDROID, now) }
            tx.transactional { repository.register(2, "shared-device", DevicePlatform.ANDROID, now) }

            assertEquals(1, tx.transactional { repository.findAllForUser(1) }.size)
            assertEquals(1, tx.transactional { repository.findAllForUser(2) }.size)
        }
    }
}
