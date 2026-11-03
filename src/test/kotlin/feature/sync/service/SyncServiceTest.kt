/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.service

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.sync.SyncTestHarness
import com.mozgobolt.feature.sync.data.repository.SYNC_USER_LOCK_NAMESPACE
import com.mozgobolt.feature.sync.domain.model.SyncChange
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.withRealSyncDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SyncServiceTest {
    @Test
    fun `recording a change makes it visible to changesSince`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            harness.service.recordChange(1, SyncEntityType.COMPANY, 42, SyncOperation.UPSERT)

            val page = harness.service.changesSince(userId = 1, cursor = 0, limit = null)

            assertEquals(1, page.events.size)
            val event = page.events.first()
            assertEquals(SyncEntityType.COMPANY, event.entityType)
            assertEquals(42, event.entityId)
            assertEquals(SyncOperation.UPSERT, event.operation)
            assertEquals(page.nextCursor, event.id)
        }
    }

    @Test
    fun `recordChanges acquires recipient locks in order and preserves same-user input order`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            harness.service.recordChanges(
                listOf(
                    SyncChange(2, SyncEntityType.VEHICLE, 20, SyncOperation.UPSERT),
                    SyncChange(1, SyncEntityType.COMPANY, 10, SyncOperation.UPSERT),
                    SyncChange(2, SyncEntityType.VEHICLE, 21, SyncOperation.DELETE),
                    SyncChange(1, SyncEntityType.COMPANY, 11, SyncOperation.DELETE),
                ),
            )

            val userOne = harness.service.changesSince(userId = 1, cursor = 0, limit = null)
            val userTwo = harness.service.changesSince(userId = 2, cursor = 0, limit = null)

            assertEquals(listOf(10, 11), userOne.events.map { it.entityId })
            assertEquals(listOf(20, 21), userTwo.events.map { it.entityId })
            assertTrue(userOne.events.last().id < userTwo.events.first().id)
        }
    }

    @Test
    fun `recordChanges rolls back all recipients when one event cannot be persisted`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            assertFailsWith<SQLException> {
                harness.service.recordChanges(
                    listOf(
                        SyncChange(1, SyncEntityType.VEHICLE, 10, SyncOperation.UPSERT),
                        SyncChange(Int.MAX_VALUE, SyncEntityType.VEHICLE, 20, SyncOperation.UPSERT),
                    ),
                )
            }

            val userOneEvents =
                harness.service
                    .changesSince(userId = 1, cursor = 0, limit = null)
                    .events
            assertTrue(userOneEvents.isEmpty())
        }
    }

    @Test
    fun `concurrent batches with opposite recipient input order complete without deadlock`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            coroutineScope {
                val first =
                    async(Dispatchers.IO) {
                        harness.service.recordChanges(
                            listOf(
                                SyncChange(1, SyncEntityType.VEHICLE, 101, SyncOperation.UPSERT),
                                SyncChange(2, SyncEntityType.VEHICLE, 201, SyncOperation.UPSERT),
                            ),
                        )
                    }
                val second =
                    async(Dispatchers.IO) {
                        harness.service.recordChanges(
                            listOf(
                                SyncChange(2, SyncEntityType.VEHICLE, 202, SyncOperation.UPSERT),
                                SyncChange(1, SyncEntityType.VEHICLE, 102, SyncOperation.UPSERT),
                            ),
                        )
                    }

                withTimeout(5_000) {
                    first.await()
                    second.await()
                }
            }

            val userOneEvents = harness.service.changesSince(userId = 1, cursor = 0, limit = null).events
            val userTwoEvents = harness.service.changesSince(userId = 2, cursor = 0, limit = null).events
            val events = (userOneEvents + userTwoEvents).associateBy { it.entityId }
            assertTrue(events.getValue(101).id < events.getValue(201).id)
            assertTrue(events.getValue(102).id < events.getValue(202).id)
        }
    }

    @Test
    fun `changesSince only returns events after the given cursor`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 1, SyncOperation.UPSERT)
            val firstPage = harness.service.changesSince(userId = 1, cursor = 0, limit = null)
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 2, SyncOperation.UPSERT)

            val secondPage = harness.service.changesSince(userId = 1, cursor = firstPage.nextCursor, limit = null)

            assertEquals(1, secondPage.events.size)
            assertEquals(2, secondPage.events.first().entityId)
        }
    }

    @Test
    fun `same-user event writes wait for earlier transaction before allocating ids`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            coroutineScope {
                val firstEventId = CompletableDeferred<Long>()
                val allowFirstCommit = CompletableDeferred<Unit>()
                val secondAttemptStarted = CompletableDeferred<Unit>()

                val first =
                    async(Dispatchers.IO) {
                        harness.tx.transactional {
                            val event =
                                harness.repository.record(
                                    userId = 1,
                                    entityType = SyncEntityType.COMPANY,
                                    entityId = 1,
                                    operation = SyncOperation.UPSERT,
                                )
                            firstEventId.complete(event.id)
                            allowFirstCommit.await()
                        }
                    }

                val firstId = firstEventId.await()
                val second =
                    async(Dispatchers.IO) {
                        secondAttemptStarted.complete(Unit)
                        harness.service.recordChange(
                            userId = 1,
                            entityType = SyncEntityType.COMPANY,
                            entityId = 2,
                            operation = SyncOperation.UPSERT,
                        )
                    }

                try {
                    secondAttemptStarted.await()
                    harness.awaitWaitingForUserSyncLock(userId = 1)
                    val notYetCommitted = harness.service.changesSince(userId = 1, cursor = firstId - 1, limit = 10)
                    assertTrue(notYetCommitted.events.isEmpty())
                } finally {
                    allowFirstCommit.complete(Unit)
                }

                first.await()
                second.await()

                val page = harness.service.changesSince(userId = 1, cursor = firstId - 1, limit = 10)
                assertEquals(listOf(1, 2), page.events.map { it.entityId })
                assertEquals(page.events.last().id, page.nextCursor)
            }
        }
    }

    @Test
    fun `a rolled back event is invisible and releases its per-user lock`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            coroutineScope {
                val firstEventId = CompletableDeferred<Long>()
                val allowRollback = CompletableDeferred<Unit>()
                val secondAttemptStarted = CompletableDeferred<Unit>()

                val first =
                    async(Dispatchers.IO) {
                        runSuspendCatching {
                            harness.tx.transactional {
                                val event =
                                    harness.repository.record(
                                        userId = 1,
                                        entityType = SyncEntityType.COMPANY,
                                        entityId = 1,
                                        operation = SyncOperation.UPSERT,
                                    )
                                firstEventId.complete(event.id)
                                allowRollback.await()
                                error("force the first event transaction to roll back")
                            }
                        }
                    }

                val failedEventId = withTimeout(5.seconds) { firstEventId.await() }
                val second =
                    async(Dispatchers.IO) {
                        secondAttemptStarted.complete(Unit)
                        harness.service.recordChange(
                            userId = 1,
                            entityType = SyncEntityType.COMPANY,
                            entityId = 2,
                            operation = SyncOperation.UPSERT,
                        )
                    }

                try {
                    withTimeout(5.seconds) { secondAttemptStarted.await() }
                    harness.awaitWaitingForUserSyncLock(userId = 1)
                    allowRollback.complete(Unit)

                    assertTrue(withTimeout(5.seconds) { first.await() }.isFailure)
                    second.await()

                    val page = harness.service.changesSince(userId = 1, cursor = 0, limit = 10)
                    assertEquals(listOf(2), page.events.map { it.entityId })
                    assertTrue(page.events.single().id > failedEventId, "rolled-back sequence values may leave gaps")
                    assertEquals(page.events.single().id, page.nextCursor)
                } finally {
                    allowRollback.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `a business mutation and its sync event roll back together`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            val companyRepository = CompanyRepositoryI()
            var companyId: Int? = null

            assertFailsWith<IllegalStateException> {
                harness.tx.transactional {
                    val company =
                        companyRepository.create(
                            name = "Sync rollback",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    companyId = company.id
                    harness.service.recordChange(
                        userId = 1,
                        entityType = SyncEntityType.COMPANY,
                        entityId = company.id,
                        operation = SyncOperation.UPSERT,
                    )
                    error("force the caller's transaction to roll back")
                }
            }

            val persistedCompany = harness.tx.transactional { companyRepository.findById(checkNotNull(companyId)) }
            assertNull(persistedCompany)
            val remainingEvents = harness.service.changesSince(userId = 1, cursor = 0, limit = 10).events
            assertTrue(remainingEvents.isEmpty())
        }
    }

    @Test
    fun `changesSince never returns another user's events`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 1, SyncOperation.UPSERT)
            harness.service.recordChange(2, SyncEntityType.VEHICLE, 2, SyncOperation.UPSERT)

            val page = harness.service.changesSince(userId = 1, cursor = 0, limit = null)

            assertEquals(1, page.events.size)
            assertEquals(1, page.events.first().userId)
        }
    }

    private suspend fun SyncTestHarness.awaitWaitingForUserSyncLock(userId: Int) {
        withTimeout(5.seconds) {
            while (!tx.transactional { isWaitingForUserSyncLock(userId) }) {
                delay(10.milliseconds)
            }
        }
    }

    private suspend fun SyncTestHarness.isWaitingForUserSyncLock(userId: Int): Boolean =
        tx.transactional {
            val isWaiting =
                TransactionManager.current().exec(
                    stmt =
                        """
                            |SELECT EXISTS (
                            |    SELECT 1
                            |    FROM pg_locks
                            |    WHERE locktype = 'advisory'
                            |      AND classid = ?::oid
                            |      AND objid = ?::oid
                            |      AND objsubid = 2
                            |      AND NOT granted
                            |)
                        """.trimMargin(),
                    args =
                        listOf(
                            IntegerColumnType() to SYNC_USER_LOCK_NAMESPACE,
                            IntegerColumnType() to userId,
                        ),
                    explicitStatementType = StatementType.SELECT,
                ) { resultSet ->
                    resultSet.next() && resultSet.getBoolean(1)
                }
            isWaiting == true
        }

    @Test
    fun `changesSince with no new events returns the same cursor back`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            val page = harness.service.changesSince(userId = 1, cursor = 99, limit = null)

            assertTrue(page.events.isEmpty())
            assertEquals(99L, page.nextCursor)
        }
    }

    // Hint delivery (recordChange -> NOTIFY -> PostgresNotifyListener -> SyncEventHub) is
    // deliberately not tested at this layer — SyncServiceI has no SyncEventHub dependency at
    // all (see its class doc). See PostgresNotifyListenerTest for that path.

    @Test
    fun `a device offline across several mixed mutations catches up completely and in order on reconnect`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            // The device was last online at cursor 0. While it was offline, three different
            // features were touched, in this order.
            harness.service.recordChange(1, SyncEntityType.COMPANY, 10, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 20, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE_ASSIGNMENT, 30, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE_ASSIGNMENT, 30, SyncOperation.UPSERT) // edited again
            harness.service.recordChange(1, SyncEntityType.VEHICLE_ASSIGNMENT, 31, SyncOperation.DELETE)

            // A concurrent device's activity must never leak into this device's catch-up.
            harness.service.recordChange(2, SyncEntityType.VEHICLE, 999, SyncOperation.UPSERT)

            val page = harness.service.changesSince(userId = 1, cursor = 0, limit = null)

            assertEquals(5, page.events.size)
            assertEquals(
                listOf(
                    SyncEntityType.COMPANY to SyncOperation.UPSERT,
                    SyncEntityType.VEHICLE to SyncOperation.UPSERT,
                    SyncEntityType.VEHICLE_ASSIGNMENT to SyncOperation.UPSERT,
                    SyncEntityType.VEHICLE_ASSIGNMENT to SyncOperation.UPSERT,
                    SyncEntityType.VEHICLE_ASSIGNMENT to SyncOperation.DELETE,
                ),
                page.events.map { it.entityType to it.operation },
            )
            // Real ids, not deduplicated by entityId — the repeated edit to entity 30 appears
            // twice. Collapsing redundant pointers to the same entity is a client-side
            // optimization, if the client wants one; the backend never re-derives it (backend
            // does persistence and correctness only, never sort/group/format).
            assertEquals(listOf(10, 20, 30, 30, 31), page.events.map { it.entityId })
            assertEquals(page.nextCursor, page.events.last().id)

            // The device is now caught up; calling again with its new cursor gets nothing new.
            val followUp = harness.service.changesSince(userId = 1, cursor = page.nextCursor, limit = null)
            assertTrue(followUp.events.isEmpty())
        }
    }

    @Test
    fun `cursor pages stay ordered across interleaved global event ids`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 10, SyncOperation.UPSERT)
            harness.service.recordChange(2, SyncEntityType.VEHICLE, 90, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 11, SyncOperation.UPSERT)
            harness.service.recordChange(2, SyncEntityType.VEHICLE, 91, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 12, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 13, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 14, SyncOperation.UPSERT)

            val first = harness.service.changesSince(userId = 1, cursor = 0, limit = 2)
            assertEquals(listOf(10, 11), first.events.map { it.entityId })
            assertEquals(first.events.last().id, first.nextCursor)

            val second = harness.service.changesSince(userId = 1, cursor = first.nextCursor, limit = 2)
            assertEquals(listOf(12, 13), second.events.map { it.entityId })
            assertEquals(second.events.last().id, second.nextCursor)

            val final = harness.service.changesSince(userId = 1, cursor = second.nextCursor, limit = 2)
            assertEquals(listOf(14), final.events.map { it.entityId })
            assertEquals(final.events.last().id, final.nextCursor)

            val caughtUp = harness.service.changesSince(userId = 1, cursor = final.nextCursor, limit = 2)
            assertTrue(caughtUp.events.isEmpty())
            assertEquals(final.nextCursor, caughtUp.nextCursor)
        }
    }

    @Test
    fun `a zero or negative limit is floored at 1, never an empty page or a crash`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 1, SyncOperation.UPSERT)
            harness.service.recordChange(1, SyncEntityType.VEHICLE, 2, SyncOperation.UPSERT)

            val zeroLimit = harness.service.changesSince(userId = 1, cursor = 0, limit = 0)
            val negativeLimit = harness.service.changesSince(userId = 1, cursor = 0, limit = -100)

            assertEquals(1, zeroLimit.events.size)
            assertEquals(1, negativeLimit.events.size)
        }
    }
}
