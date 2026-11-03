/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.savedLocation.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationsTable
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationConstraints
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedLocationRepositoryRealDatabaseTest {
    @Test
    fun `created locations round trip through postgres and missing ids return null`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val created =
                tx.transactional {
                    repository.create(
                        userId = 1,
                        label = "Budapest home",
                        latitude = 47.4979,
                        longitude = 19.0402,
                        radiusKm = 2.5,
                    )
                }

            val found = assertNotNull(tx.transactional { repository.findById(created.id) })
            assertEquals(created.id, found.id)
            assertEquals(1, found.userId)
            assertEquals("Budapest home", found.label)
            assertEquals(47.4979, found.latitude)
            assertEquals(19.0402, found.longitude)
            assertEquals(2.5, found.radiusKm)
            assertTrue(found.createdAt <= Instant.now())
            assertTrue(found.createdAt >= created.createdAt.minusSeconds(1))
            assertNull(tx.transactional { repository.findById(-1) })
        }
    }

    @Test
    fun `inclusive coordinate and radius bounds persist in postgres`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val lower =
                tx.transactional {
                    repository.create(
                        userId = 1,
                        label = "Lower bounds",
                        latitude = GeoBounds.MIN_LATITUDE,
                        longitude = GeoBounds.MIN_LONGITUDE,
                        radiusKm = SavedLocationConstraints.MIN_RADIUS_KM,
                    )
                }
            val upper =
                tx.transactional {
                    repository.create(
                        userId = 1,
                        label = "Upper bounds",
                        latitude = GeoBounds.MAX_LATITUDE,
                        longitude = GeoBounds.MAX_LONGITUDE,
                        radiusKm = SavedLocationConstraints.MAX_RADIUS_KM,
                    )
                }

            assertEquals(GeoBounds.MIN_LATITUDE, lower.latitude)
            assertEquals(GeoBounds.MIN_LONGITUDE, lower.longitude)
            assertEquals(SavedLocationConstraints.MIN_RADIUS_KM, lower.radiusKm)
            assertEquals(GeoBounds.MAX_LATITUDE, upper.latitude)
            assertEquals(GeoBounds.MAX_LONGITUDE, upper.longitude)
            assertEquals(SavedLocationConstraints.MAX_RADIUS_KM, upper.radiusKm)
        }
    }

    @Test
    fun `out-of-range coordinates and radii fail their database checks`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val invalidLocations =
                listOf(
                    Triple(90.000001, 19.0402, 2.5),
                    Triple(-90.000001, 19.0402, 2.5),
                    Triple(47.4979, 180.000001, 2.5),
                    Triple(47.4979, -180.000001, 2.5),
                    Triple(47.4979, 19.0402, 0.09),
                    Triple(47.4979, 19.0402, 50.01),
                )

            invalidLocations.forEachIndexed { index, (latitude, longitude, radiusKm) ->
                val failure =
                    assertFails {
                        tx.transactional {
                            repository.create(
                                userId = 1,
                                label = "Invalid $index",
                                latitude = latitude,
                                longitude = longitude,
                                radiusKm = radiusKm,
                            )
                        }
                    }

                assertSqlState(failure, "23514")
                assertEquals(0L, tx.transactional { UserSavedLocationsTable.selectAll().count() })
            }
        }
    }

    @Test
    fun `an invalid owner foreign key rolls back earlier rows in the same transaction`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val failure =
                assertFails {
                    tx.transactional {
                        repository.create(1, "Would be partial", 47.4979, 19.0402, 2.5)
                        repository.create(Int.MAX_VALUE, "Orphan", 47.5, 19.1, 2.5)
                    }
                }

            assertSqlState(failure, "23503")
            assertEquals(0L, tx.transactional { UserSavedLocationsTable.selectAll().count() })
        }
    }

    @Test
    fun `user queries remain isolated and collection lookup handles empty input`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val ids =
                tx.transactional {
                    listOf(
                        repository.create(1, "Home", 47.4979, 19.0402, 2.5).id,
                        repository.create(1, "Work", 47.5, 19.1, 1.0).id,
                        repository.create(2, "Shop", 47.6, 19.2, 5.0).id,
                    )
                }

            val firstUserLocations = tx.transactional { repository.findAllForUser(1) }
            val secondUserLocations = tx.transactional { repository.findAllForUser(2) }
            val missingUserLocations = tx.transactional { repository.findAllForUser(999) }
            val selectedUsersLocations = tx.transactional { repository.findAllForUsers(listOf(1, 2, 1)) }
            val noUserLocations = tx.transactional { repository.findAllForUsers(emptyList()) }

            assertEquals(setOf(ids[0], ids[1]), firstUserLocations.map { it.id }.toSet())
            assertEquals(setOf(ids[2]), secondUserLocations.map { it.id }.toSet())
            assertTrue(missingUserLocations.isEmpty())
            assertEquals(ids.toSet(), selectedUsersLocations.map { it.id }.toSet())
            assertTrue(noUserLocations.isEmpty())
        }
    }

    @Test
    fun `delete removes only the requested location and is safe for missing ids`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = SavedLocationRepositoryI()
            val (deletedId, retainedId) =
                tx.transactional {
                    val deleted = repository.create(1, "Delete me", 47.4979, 19.0402, 2.5)
                    val retained = repository.create(1, "Keep me", 47.5, 19.1, 1.0)
                    deleted.id to retained.id
                }

            val deletionResults =
                tx.transactional {
                    repository.delete(deletedId) to repository.delete(-1)
                }

            assertTrue(deletionResults.first)
            assertFalse(deletionResults.second)
            assertNull(tx.transactional { repository.findById(deletedId) })
            assertEquals(retainedId, tx.transactional { repository.findById(retainedId)?.id })
        }
    }

    private fun assertSqlState(
        failure: Throwable,
        sqlState: String,
    ) {
        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>()
                .any { it.sqlState == sqlState },
            "expected PostgreSQL SQLSTATE $sqlState",
        )
    }
}
