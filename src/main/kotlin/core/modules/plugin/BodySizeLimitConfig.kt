package com.besa.boardShare.core.modules.plugin

import com.besa.boardShare.core.domain.validation.ValidatedRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

enum class BodyLimit(val bytes: Long) {
    TINY(1L * 1024),            // 1 KB  — login, register
    SMALL(16L * 1024),          // 16 KB — simple JSON, tokens
    MEDIUM(256L * 1024),        // 256 KB — rich JSON (descriptions, lists)
    LARGE(5L * 1024 * 1024),    // 5 MB  — image uploads
    GLOBAL(10L * 1024 * 1024),  // 10 MB — absolute safety net
}

fun Application.configureGlobalBodyLimit() {
    intercept(ApplicationCallPipeline.Plugins) {
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > BodyLimit.GLOBAL.bytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            finish()
        }
    }
}

inline fun <reified T : ValidatedRequest> Route.validatedPost(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    crossinline handler: suspend RoutingContext.(T) -> Unit
) {
    post(path) {
        if (exceedsLimit(limit)) return@post
        val request = call.receive<T>()
        handler(request)
    }
}

inline fun <reified T : ValidatedRequest> Route.validatedPut(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    crossinline handler: suspend RoutingContext.(T) -> Unit
) {
    put(path) {
        if (exceedsLimit(limit)) return@put
        val request = call.receive<T>()
        handler(request)
    }
}

inline fun <reified T : ValidatedRequest> Route.validatedPatch(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    crossinline handler: suspend RoutingContext.(T) -> Unit
) {
    patch(path) {
        if (exceedsLimit(limit)) return@patch
        val request = call.receive<T>()
        handler(request)
    }
}

fun Route.limitedPost(
    path: String,
    limit: BodyLimit = BodyLimit.TINY,
    body: suspend RoutingContext.() -> Unit
) {
    post(path) {
        if (exceedsLimit(limit)) return@post
        body()
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
