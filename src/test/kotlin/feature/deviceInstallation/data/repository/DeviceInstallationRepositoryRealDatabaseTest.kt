/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.deviceInstallation.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationConstraints
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises global FID uniqueness, ownership transfer, registration revisions, and the real
 * PostgreSQL constraints and delete predicates behind push delivery.
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
            assertEquals(2L, allInstallations.single().registrationVersion)
        }
    }

    @Test
    fun `registering an existing installation updates mutable fields and preserves its creation time`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val createdAt = Instant.parse("2026-01-01T10:00:00Z")
            val updatedAt = createdAt.plusSeconds(60)

            val created =
                tx.transactional {
                    repository.register(1, "device-a", DevicePlatform.ANDROID, createdAt)
                }
            val refreshed =
                tx.transactional {
                    repository.register(1, "device-a", DevicePlatform.IOS, updatedAt)
                }

            assertEquals(created.id, refreshed.id)
            assertEquals(createdAt, refreshed.createdAt)
            assertEquals(updatedAt, refreshed.updatedAt)
            assertEquals(DevicePlatform.IOS, refreshed.platform)
            assertEquals(2L, refreshed.registrationVersion)
        }
    }

    @Test
    fun `registering the same installation id for another user transfers its ownership`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val createdAt = Instant.parse("2026-01-01T10:00:00Z")
            val transferredAt = createdAt.plusSeconds(60)

            val original =
                tx.transactional {
                    repository.register(1, "shared-device", DevicePlatform.ANDROID, createdAt)
                }
            val transferred =
                tx.transactional {
                    repository.register(2, "shared-device", DevicePlatform.IOS, transferredAt)
                }

            assertEquals(original.id, transferred.id)
            assertEquals(createdAt, transferred.createdAt)
            assertEquals(transferredAt, transferred.updatedAt)
            assertEquals(DevicePlatform.IOS, transferred.platform)
            assertEquals(original.registrationVersion + 1, transferred.registrationVersion)
            assertTrue(tx.transactional { repository.findAllForUser(1) }.isEmpty())
            assertEquals(listOf(transferred), tx.transactional { repository.findAllForUser(2) })
        }
    }

    @Test
    fun `concurrent registrations of one installation for different users still leave one current owner`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val now = Instant.now()
            val start = CyclicBarrier(2)

            val results =
                coroutineScope {
                    val first =
                        async(Dispatchers.IO) {
                            start.await(10, TimeUnit.SECONDS)
                            tx.transactional { repository.register(1, "racing-fid", DevicePlatform.ANDROID, now) }
                        }
                    val second =
                        async(Dispatchers.IO) {
                            start.await(10, TimeUnit.SECONDS)
                            tx.transactional { repository.register(2, "racing-fid", DevicePlatform.IOS, now) }
                        }
                    listOf(first.await(), second.await())
                }

            assertEquals(setOf(1, 2), results.map { it.userId }.toSet())
            val persisted =
                tx.transactional {
                    repository.findAllForUser(1) + repository.findAllForUser(2)
                }
            assertEquals(1, persisted.size)
            assertTrue(persisted.single().userId in setOf(1, 2))
            assertEquals(2L, persisted.single().registrationVersion)
        }
    }

    @Test
    fun `user-scoped unregister cannot delete another user's installation`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val now = Instant.now()
            tx.transactional {
                repository.register(1, "shared-id", DevicePlatform.ANDROID, now)
                repository.register(1, "keep-id", DevicePlatform.IOS, now)
            }

            assertTrue(tx.transactional { repository.findAllForUser(3) }.isEmpty())
            assertFalse(tx.transactional { repository.deleteForUser(2, "shared-id") })
            assertEquals(
                setOf("shared-id", "keep-id"),
                tx.transactional { repository.findAllForUser(1) }.map { it.installationId }.toSet(),
            )
            assertTrue(tx.transactional { repository.deleteForUser(1, "shared-id") })
            assertFalse(tx.transactional { repository.deleteForUser(1, "shared-id") })
        }
    }

    @Test
    fun `stale cleanup cannot delete a transferred registration and current cleanup is conditional`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val now = Instant.parse("2026-01-01T10:00:00Z")
            val original =
                tx.transactional {
                    repository.register(1, "shared-id", DevicePlatform.ANDROID, now)
                }
            val transferred =
                tx.transactional {
                    repository.register(2, "shared-id", DevicePlatform.IOS, now.plusSeconds(1))
                }

            assertFalse(tx.transactional { repository.deleteIfCurrent(original) })
            assertFalse(
                tx.transactional {
                    repository.deleteIfCurrent(original.copy(userId = 2))
                },
                "matching user and token are insufficient if the registration version is stale",
            )
            assertEquals(listOf(transferred), tx.transactional { repository.findAllForUser(2) })
            assertTrue(tx.transactional { repository.deleteIfCurrent(transferred) })
            assertFalse(tx.transactional { repository.deleteIfCurrent(transferred) })
            assertTrue(tx.transactional { repository.findAllForUser(2) }.isEmpty())
        }
    }

    @Test
    fun `the declared maximum installation id length can be registered with a bounded unique index`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val installationId = "a".repeat(DeviceInstallationConstraints.INSTALLATION_ID_MAX_LENGTH)

            val registered =
                tx.transactional {
                    repository.register(1, installationId, DevicePlatform.ANDROID, Instant.now())
                }

            assertEquals(installationId, registered.installationId)
            assertEquals(listOf(registered), tx.transactional { repository.findAllForUser(1) })
        }
    }

    @Test
    fun `registration rejects a missing user through its database foreign key`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = DeviceInstallationRepositoryI()
            val failure =
                assertFails {
                    tx.transactional {
                        repository.register(
                            userId = Int.MAX_VALUE,
                            installationId = "orphan-installation",
                            platform = DevicePlatform.ANDROID,
                            now = Instant.now(),
                        )
                    }
                }

            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<SQLException>()
                    .any { it.sqlState == "23503" },
                "expected PostgreSQL foreign-key violation for a missing user",
            )
            assertTrue(tx.transactional { repository.findAllForUser(1) }.isEmpty())
        }
    }
}
