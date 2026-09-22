package com.mozgobolt.feature.sync.service

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.withRealSyncDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `a within-range limit is respected as an ordinary page size`() {
        skipIfNoDocker()

        withRealSyncDatabase { harness ->
            repeat(5) {
                harness.service.recordChange(1, SyncEntityType.VEHICLE, it, SyncOperation.UPSERT)
            }

            val page = harness.service.changesSince(userId = 1, cursor = 0, limit = 2)

            assertEquals(2, page.events.size)
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
