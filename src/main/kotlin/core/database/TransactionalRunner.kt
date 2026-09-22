package com.mozgobolt.core.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Atomic transaction boundary for the service layer. Repository methods that call
 * `suspendTransaction` themselves join an active outer transaction (Exposed reuses
 * it on the same coroutine), so nesting inside [transactional] is safe.
 */
interface TransactionalRunner {
    suspend fun <T> transactional(block: suspend () -> T): T
}

class ExposedTransactionalRunner(
    private val database: Database,
) : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = suspendTransaction(db = database) { block() }
}
