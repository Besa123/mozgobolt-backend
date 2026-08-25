package com.besa.shelflife.core.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Provides atomic transaction boundaries at the service layer.
 *
 * When a service method needs multiple repository calls to be atomic,
 * wrap them in [transactional]. Repository methods that already call
 * `suspendTransaction` will detect the active transaction and join it
 * (Exposed reuses the outer transaction on the same coroutine).
 *
 * Usage:
 * ```kotlin
 * class MyService(private val tx: TransactionalRunner) {
 *     suspend fun doAtomicWork() = tx.transactional {
 *         repo.step1()  // joins this transaction
 *         repo.step2()  // joins this transaction
 *     }
 * }
 * ```
 */
interface TransactionalRunner {
    suspend fun <T> transactional(block: suspend () -> T): T
}

class ExposedTransactionalRunner(
    private val database: Database,
) : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = suspendTransaction(db = database) { block() }
}
