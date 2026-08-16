package com.besa.boardShare.core.modules.plugin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

enum class BodyLimit(val bytes: Long) {
    TINY(1L * 1024),
    SMALL(16L * 1024),
    MEDIUM(256L * 1024),
    LARGE(5L * 1024 * 1024),
    GLOBAL(10L * 1024 * 1024),
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

fun Route.limitedPost(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    body: suspend RoutingContext.() -> Unit
) {
    post(path) {
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > limit.bytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        body()
    }
}


fun Route.limitedPut(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    body: suspend RoutingContext.() -> Unit
) {
    put(path) {
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > limit.bytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@put
        }
        body()
    }
}

fun Route.limitedPatch(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    body: suspend RoutingContext.() -> Unit
) {
    patch(path) {
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > limit.bytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@patch
        }
        body()
    }
}
