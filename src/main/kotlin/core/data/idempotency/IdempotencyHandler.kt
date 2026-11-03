/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.idempotency

import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.json.Json

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
private const val MAX_KEY_LENGTH = 64
private val KEY_PATTERN = Regex("^[a-zA-Z0-9\\-_]+$")
private val logger = KotlinLogging.logger {}

data class IdempotentResult(
    val statusCode: HttpStatusCode,
    val body: String,
    val afterCommitBestEffort: (suspend () -> Unit)? = null,
    /** Set false for retryable failures that performed no business mutation. */
    val shouldCacheResponse: Boolean = true,
)

suspend fun RoutingContext.idempotent(
    store: IdempotencyStore,
    requestFingerprint: String = "",
    handler: suspend RoutingContext.() -> IdempotentResult,
) {
    val key =
        call.extractIdempotencyKey() ?: run {
            val result = handler()
            result.runAfterCommitBestEffort()
            call.respondText(result.body, ContentType.Application.Json, result.statusCode)
            return
        }

    if (key.length > MAX_KEY_LENGTH || !key.matches(KEY_PATTERN)) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "INVALID_IDEMPOTENCY_KEY"))
        return
    }

    val fingerprintHash = store.hashFingerprint(requestFingerprint)
    when (val execution = store.execute(key, fingerprintHash) { handler() }) {
        is IdempotencyExecution.Completed -> {
            if (execution.wasReplayed) call.response.header("X-Idempotency-Replayed", "true")
            execution.result.runAfterCommitBestEffort()
            call.respondText(
                execution.result.body,
                ContentType.Application.Json,
                execution.result.statusCode,
            )
        }

        is IdempotencyExecution.Rejected -> {
            call.respond(
                HttpStatusCode.fromValue(execution.statusCode),
                ErrorResponse(error = execution.error),
            )
        }
    }
}

inline fun <reified T> idempotentResult(
    statusCode: HttpStatusCode,
    body: T,
    noinline afterCommitBestEffort: (suspend () -> Unit)? = null,
    shouldCacheResponse: Boolean = true,
): IdempotentResult =
    IdempotentResult(
        statusCode = statusCode,
        body = Json.encodeToString(body),
        afterCommitBestEffort = afterCommitBestEffort,
        shouldCacheResponse = shouldCacheResponse,
    )

private fun ApplicationCall.extractIdempotencyKey(): String? {
    val key = request.header(IDEMPOTENCY_KEY_HEADER)
    return key?.takeIf { it.isNotBlank() }
}

@Suppress("RedundantSuspendModifier") // The callback is suspend and invoked inside the inline Result wrapper.
private suspend fun IdempotentResult.runAfterCommitBestEffort() {
    afterCommitBestEffort?.let { action ->
        runSuspendCatching { action() }
            .onFailure { failure ->
                logger.error {
                    "Idempotent post-commit action failed (${failure.javaClass.simpleName})"
                }
            }
    }
}
