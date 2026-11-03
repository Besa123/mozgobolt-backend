/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Atomic transaction boundary for the service layer. Repository methods that call
 * `suspendTransaction` themselves join an active outer transaction (Exposed reuses
 * it on the same coroutine), so nesting inside [transactional] is safe.
 * This is the transaction boundary used by application services.
 */
interface TransactionalRunner {
    suspend fun <T> transactional(block: suspend () -> T): T
}

/** Implements [TransactionalRunner] with Exposed's coroutine-aware JDBC transaction. */
class ExposedTransactionalRunner(
    private val database: Database,
    private val databaseDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T =
        withContext(databaseDispatcher) {
            suspendTransaction(db = database) { block() }
        }
}
