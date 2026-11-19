/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.Connection

/**
 * Atomic transaction boundary for the service layer. Repository methods that call
 * `suspendTransaction` themselves join an active outer transaction (Exposed reuses
 * it on the same coroutine), so nesting inside [transactional] is safe.
 * This is the transaction boundary used by application services.
 */
interface TransactionalRunner {
    suspend fun <T> transactional(block: suspend () -> T): T

    /**
     * Runs a read-only unit of work against one PostgreSQL REPEATABLE READ snapshot.
     * Call this at the outer transaction boundary; an existing transaction cannot be upgraded.
     */
    suspend fun <T> transactionalReadOnlySnapshot(block: suspend () -> T): T =
        error("This transaction runner does not support read-only snapshot transactions")

    /**
     * Runs [block] as one unit of work and either commits or explicitly rolls back while returning its value.
     * [TransactionOutcome.Rollback] aborts the full active transaction; use this only at the owning boundary,
     * not to emulate a nested savepoint.
     */
    suspend fun <T> transactionalWithOutcome(block: suspend () -> TransactionOutcome<T>): T
}

/** Explicit commit decision for a result-returning transaction. */
sealed interface TransactionOutcome<out T> {
    /** The value returned to the caller whether the transaction commits or rolls back. */
    val value: T

    /** Commits the transaction and returns [value]. */
    data class Commit<T>(
        override val value: T,
    ) : TransactionOutcome<T>

    /** Rolls back the transaction and returns [value]. */
    data class Rollback<T>(
        override val value: T,
    ) : TransactionOutcome<T>
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

    override suspend fun <T> transactionalReadOnlySnapshot(block: suspend () -> T): T =
        withContext(databaseDispatcher) {
            suspendTransaction(
                db = database,
                transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
                readOnly = true,
            ) { block() }
        }

    override suspend fun <T> transactionalWithOutcome(block: suspend () -> TransactionOutcome<T>): T =
        withContext(databaseDispatcher) {
            suspendTransaction(db = database) {
                when (val outcome = block()) {
                    is TransactionOutcome.Commit -> {
                        outcome.value
                    }

                    is TransactionOutcome.Rollback -> {
                        rollback()
                        outcome.value
                    }
                }
            }
        }
}
