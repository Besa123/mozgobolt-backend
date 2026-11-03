/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehiclePing.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehiclePing.withRealVehiclePingDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehiclePingRepositoryRealDatabaseTest {
    @Test
    fun `purge removes cooldown rows strictly older than cutoff`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()
            val cutoff = Instant.parse("2026-01-01T00:00:00Z")
            harness.tx.transactional {
                harness.pingRepository.recordLatest(vehicleId, buyerUserId = 1, sentAt = cutoff.minusSeconds(1))
                harness.pingRepository.recordLatest(vehicleId, buyerUserId = 2, sentAt = cutoff)
            }

            val deleted = harness.tx.transactional { harness.pingRepository.purgeOlderThan(cutoff) }

            assertEquals(1, deleted)
            assertNull(harness.tx.transactional { harness.pingRepository.lastSentAt(vehicleId, buyerUserId = 1) })
            assertEquals(
                cutoff,
                harness.tx.transactional { harness.pingRepository.lastSentAt(vehicleId, buyerUserId = 2) },
            )
        }
    }

    @Test
    fun `upsert stores one latest cooldown row per buyer and vehicle`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()
            val firstSentAt = Instant.parse("2026-01-01T00:00:00Z")
            val latestForBuyer = firstSentAt.plusSeconds(60)
            val latestForOtherBuyer = firstSentAt.plusSeconds(120)

            harness.tx.transactional {
                harness.pingRepository.recordLatest(vehicleId, buyerUserId = 1, sentAt = firstSentAt)
                harness.pingRepository.recordLatest(vehicleId, buyerUserId = 1, sentAt = latestForBuyer)
                harness.pingRepository.recordLatest(vehicleId, buyerUserId = 2, sentAt = latestForOtherBuyer)
            }

            assertEquals(
                latestForBuyer,
                harness.tx.transactional { harness.pingRepository.lastSentAt(vehicleId, buyerUserId = 1) },
            )
            assertEquals(
                latestForOtherBuyer,
                harness.tx.transactional { harness.pingRepository.lastSentAt(vehicleId, buyerUserId = 2) },
            )
            assertNull(harness.tx.transactional { harness.pingRepository.lastSentAt(vehicleId, 999) })
            assertEquals(2L, harness.tx.transactional { VehiclePingsTable.selectAll().count() })
            assertEquals(
                1L,
                harness.tx.transactional {
                    VehiclePingsTable
                        .selectAll()
                        .where {
                            (VehiclePingsTable.vehicleId eq vehicleId) and
                                (VehiclePingsTable.buyerUserId eq 1)
                        }.count()
                },
            )
        }
    }

    @Test
    fun `missing vehicle and buyer foreign keys reject ping persistence`() {
        skipIfNoDocker()

        withRealVehiclePingDatabase { harness ->
            val vehicleId = harness.seedActiveVehicleDrivenByUserTwo()
            val sentAt = Instant.parse("2026-01-01T00:00:00Z")

            val missingVehicleFailure =
                assertFails {
                    harness.tx.transactional {
                        harness.pingRepository.recordLatest(Int.MAX_VALUE, buyerUserId = 1, sentAt = sentAt)
                    }
                }
            assertSqlState(missingVehicleFailure, "23503")

            val missingBuyerFailure =
                assertFails {
                    harness.tx.transactional {
                        harness.pingRepository.recordLatest(
                            vehicleId,
                            buyerUserId = Int.MAX_VALUE,
                            sentAt = sentAt,
                        )
                    }
                }
            assertSqlState(missingBuyerFailure, "23503")

            assertEquals(0L, harness.tx.transactional { VehiclePingsTable.selectAll().count() })
            assertEquals(
                0L,
                harness.tx.transactional {
                    VehiclePingsTable
                        .selectAll()
                        .where {
                            (VehiclePingsTable.vehicleId eq vehicleId) and
                                (VehiclePingsTable.buyerUserId eq 1)
                        }.count()
                },
            )
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
