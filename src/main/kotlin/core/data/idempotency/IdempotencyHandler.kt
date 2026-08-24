package com.besa.shelflife.core.data.idempotency

import com.besa.shelflife.core.routing.dto.response.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
private const val MAX_KEY_LENGTH = 64
private const val LOCK_TIMEOUT_SECONDS = 300L
private val KEY_PATTERN = Regex("^[a-zA-Z0-9\\-_]+$")

data class IdempotentResult(
    val statusCode: HttpStatusCode,
    val body: String,
)

suspend fun RoutingContext.idempotent(
    store: IdempotencyStore,
    requestFingerprint: String = "",
    handler: suspend RoutingContext.() -> IdempotentResult
) {
    val key = call.extractIdempotencyKey() ?: run {
        val result = handler()
        call.respondText(result.body, ContentType.Application.Json, result.statusCode)
        return
    }

    val fingerprintHash = hashFingerprint(requestFingerprint)

    when (val state = lookupKey(store, key, fingerprintHash)) {
        is KeyState.Completed -> {
            call.response.header("X-Idempotency-Replayed", "true")
            call.respondText(state.body, ContentType.Application.Json, state.statusCode)
        }

        is KeyState.Rejected -> {
            call.respond(state.statusCode, ErrorResponse(error = state.error))
        }

        is KeyState.Available -> {
            executeWithLock(store, key, fingerprintHash, handler)
        }
    }
}

inline fun <reified T> idempotentResult(statusCode: HttpStatusCode, body: T): IdempotentResult {
    return IdempotentResult(statusCode, Json.encodeToString(body))
}


private sealed interface KeyState {
    data class Completed(val statusCode: HttpStatusCode, val body: String) : KeyState
    data class Rejected(val statusCode: HttpStatusCode, val error: String) : KeyState
    data object Available : KeyState
}


private fun ApplicationCall.extractIdempotencyKey(): String? {
    val key = request.header(IDEMPOTENCY_KEY_HEADER)
    return key?.takeIf { it.isNotBlank() }
}

private suspend fun lookupKey(
    store: IdempotencyStore,
    key: String,
    fingerprintHash: String
): KeyState {
    if (key.length > MAX_KEY_LENGTH || !key.matches(KEY_PATTERN)) {
        return KeyState.Rejected(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
    }

    val existing = store.find(key) ?: return KeyState.Available

    if (existing.requestFingerprint.isNotEmpty() && existing.requestFingerprint != fingerprintHash) {
        return KeyState.Rejected(HttpStatusCode.UnprocessableEntity, "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD")
    }

    val statusCode = existing.statusCode
        ?: return handleLockedKey(store, key, existing.createdAt)

    return KeyState.Completed(
        statusCode = HttpStatusCode.fromValue(statusCode),
        body = existing.responseBody
    )
}

private suspend fun handleLockedKey(
    store: IdempotencyStore,
    key: String,
    createdAt: Instant
): KeyState {
    val isZombie = createdAt.isBefore(Instant.now().minusSeconds(LOCK_TIMEOUT_SECONDS))

    if (!isZombie) {
        return KeyState.Rejected(HttpStatusCode.Conflict, "REQUEST_IN_PROGRESS")
    }

    store.deleteStaleLock(key)
    return KeyState.Available
}

private suspend fun RoutingContext.executeWithLock(
    store: IdempotencyStore,
    key: String,
    fingerprintHash: String,
    handler: suspend RoutingContext.() -> IdempotentResult
) {
    val inserted = store.acquireLock(key, fingerprintHash)

    if (!inserted) {
        call.respond(HttpStatusCode.Conflict, ErrorResponse(error = "REQUEST_IN_PROGRESS"))
        return
    }

    val result = handler()

    store.complete(key, result.statusCode.value, result.body)

    call.respondText(result.body, ContentType.Application.Json, result.statusCode)
}

private fun hashFingerprint(fingerprint: String): String {
    if (fingerprint.isEmpty()) return ""
    return MessageDigest.getInstance("SHA-256")
        .digest(fingerprint.toByteArray())
        .toHexString()
}
