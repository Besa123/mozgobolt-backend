package com.shelflife.core.modules.plugin

import com.shelflife.core.domain.validation.ValidatedRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.withTimeout

/**
 * Route builders enforcing a body-size limit and request timeout per declaration; the
 * `validated*` variants also deserialize into a [ValidatedRequest], whose `validate()`
 * runs via the RequestValidation plugin on receive — handlers never see an invalid body.
 * Pick the tightest [BodyLimit]/[RequestTimeout] per endpoint; don't default blindly.
 */
inline fun <reified T : ValidatedRequest> Route.validatedPost(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    timeout: RequestTimeout = RequestTimeout.STANDARD,
    crossinline handler: suspend RoutingContext.(T) -> Unit,
) {
    post(path) {
        if (exceedsLimit(limit)) return@post
        val request = call.receive<T>()
        withTimeout(timeout.duration) { handler(request) }
    }
}

inline fun <reified T : ValidatedRequest> Route.validatedPut(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    timeout: RequestTimeout = RequestTimeout.STANDARD,
    crossinline handler: suspend RoutingContext.(T) -> Unit,
) {
    put(path) {
        if (exceedsLimit(limit)) return@put
        val request = call.receive<T>()
        withTimeout(timeout.duration) { handler(request) }
    }
}

inline fun <reified T : ValidatedRequest> Route.validatedPatch(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    timeout: RequestTimeout = RequestTimeout.STANDARD,
    crossinline handler: suspend RoutingContext.(T) -> Unit,
) {
    patch(path) {
        if (exceedsLimit(limit)) return@patch
        val request = call.receive<T>()
        withTimeout(timeout.duration) { handler(request) }
    }
}

fun Route.limitedPost(
    path: String,
    limit: BodyLimit = BodyLimit.TINY,
    timeout: RequestTimeout = RequestTimeout.STANDARD,
    body: suspend RoutingContext.() -> Unit,
) {
    post(path) {
        if (exceedsLimit(limit)) return@post
        withTimeout(timeout.duration) { body() }
    }
}

fun Route.limitedDelete(
    path: String,
    limit: BodyLimit = BodyLimit.TINY,
    timeout: RequestTimeout = RequestTimeout.FAST,
    body: suspend RoutingContext.() -> Unit,
) {
    delete(path) {
        if (exceedsLimit(limit)) return@delete
        withTimeout(timeout.duration) { body() }
    }
}

suspend fun RoutingContext.exceedsLimit(limit: BodyLimit): Boolean {
    val contentLength = call.request.contentLength()
    if (contentLength != null && contentLength > limit.bytes) {
        call.respond(HttpStatusCode.PayloadTooLarge)
        return true
    }
    return false
}
