/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.feature.sync.domain.SyncRepository
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncEvent
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.domain.model.SyncPage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncServiceLimitTest {
    private class CapturingSyncRepository : SyncRepository {
        val requestedLimits = mutableListOf<Int>()

        override suspend fun record(
            userId: Int,
            entityType: SyncEntityType,
            entityId: Int,
            operation: SyncOperation,
            originDeviceId: String?,
        ): SyncEvent = error("not exercised")

        override suspend fun findSince(
            userId: Int,
            cursor: Long,
            limit: Int,
        ): SyncPage {
            requestedLimits += limit
            return SyncPage(events = emptyList(), nextCursor = cursor)
        }
    }

    private object InlineTransactionalRunner : TransactionalRunner {
        override suspend fun <T> transactional(block: suspend () -> T): T = block()
    }

    @Test
    fun `sync page size defaults and clamps to configured bounds`() {
        runBlocking {
            val repository = CapturingSyncRepository()
            val service = SyncServiceI(repository, InlineTransactionalRunner)

            service.changesSince(userId = 1, cursor = 0, limit = null)
            service.changesSince(userId = 1, cursor = 0, limit = Int.MAX_VALUE)
            service.changesSince(userId = 1, cursor = 0, limit = Int.MIN_VALUE)

            assertEquals(listOf(200, 500, 1), repository.requestedLimits)
        }
    }
}
