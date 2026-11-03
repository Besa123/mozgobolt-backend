/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

private const val UPLOAD_READ_CHUNK_SIZE = 8192

inline fun <reified T : ValidatedRequest> Route.validatedPost(
    path: String,
    limit: BodyLimit = BodyLimit.MEDIUM,
    timeout: RequestTimeout = RequestTimeout.STANDARD,
    crossinline handler: suspend RoutingContext.(T) -> Unit,
) {
    withRequestBodyLimit(path, HttpMethod.Post, limit) {
        if (exceedsLimit(limit)) return@withRequestBodyLimit
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
    withRequestBodyLimit(path, HttpMethod.Put, limit) {
        if (exceedsLimit(limit)) return@withRequestBodyLimit
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
    withRequestBodyLimit(path, HttpMethod.Patch, limit) {
        if (exceedsLimit(limit)) return@withRequestBodyLimit
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
    withRequestBodyLimit(path, HttpMethod.Post, limit) {
        if (exceedsLimit(limit)) return@withRequestBodyLimit
        withTimeout(timeout.duration) { body() }
    }
}

fun Route.limitedDelete(
    path: String,
    limit: BodyLimit = BodyLimit.TINY,
    timeout: RequestTimeout = RequestTimeout.FAST,
    body: suspend RoutingContext.() -> Unit,
) {
    withRequestBodyLimit(path, HttpMethod.Delete, limit) {
        if (exceedsLimit(limit)) return@withRequestBodyLimit
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

fun Route.limitedFileUploadPost(
    path: String,
    limit: BodyLimit,
    timeout: RequestTimeout = RequestTimeout.UPLOAD,
    body: suspend RoutingContext.(bytes: ByteArray) -> Unit,
) {
    withRequestBodyLimit(path, HttpMethod.Post, limit) {
        if (exceedsLimit(limit)) return@withRequestBodyLimit
        withTimeout(timeout.duration) { handleFileUpload(limit, body) }
    }
}

fun Route.limitedFileUploadPut(
    path: String,
    limit: BodyLimit,
    timeout: RequestTimeout = RequestTimeout.UPLOAD,
    body: suspend RoutingContext.(bytes: ByteArray) -> Unit,
) {
    withRequestBodyLimit(path, HttpMethod.Put, limit) {
        if (exceedsLimit(limit)) return@withRequestBodyLimit
        withTimeout(timeout.duration) { handleFileUpload(limit, body) }
    }
}

private suspend fun RoutingContext.handleFileUpload(
    limit: BodyLimit,
    body: suspend RoutingContext.(bytes: ByteArray) -> Unit,
) {
    when (val result = receiveSingleFilePart(limit)) {
        is FilePartResult.Ok -> {
            body(result.bytes)
        }

        FilePartResult.TooLarge -> {
            call.respond(HttpStatusCode.PayloadTooLarge)
        }

        FilePartResult.Missing -> {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "MISSING_FILE"))
        }

        FilePartResult.Multiple -> {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "MULTIPLE_FILES"))
        }
    }
}

private sealed interface FilePartResult {
    @Suppress("UseDataClass")
    class Ok(
        val bytes: ByteArray,
    ) : FilePartResult

    data object TooLarge : FilePartResult

    data object Missing : FilePartResult

    data object Multiple : FilePartResult
}

private suspend fun RoutingContext.receiveSingleFilePart(limit: BodyLimit): FilePartResult {
    var result: FilePartResult = FilePartResult.Missing
    var totalRead = 0L

    call.receiveMultipart().forEachPart { part ->
        if (part is PartData.FormItem && result !is FilePartResult.TooLarge) {
            totalRead += part.value.toByteArray(StandardCharsets.UTF_8).size
            if (totalRead > limit.bytes) result = FilePartResult.TooLarge
        }

        if (part is PartData.FileItem && result !is FilePartResult.TooLarge) {
            val bytes = readBoundedFilePart(part, limit.bytes - totalRead)
            if (bytes == null) {
                result = FilePartResult.TooLarge
            } else {
                totalRead += bytes.size
                result =
                    when (result) {
                        FilePartResult.Missing -> FilePartResult.Ok(bytes)
                        is FilePartResult.Ok, FilePartResult.Multiple -> FilePartResult.Multiple
                        FilePartResult.TooLarge -> FilePartResult.TooLarge
                    }
            }
        }
        part.release()
    }

    return result
}

@Suppress("RedundantSuspendModifier") // ByteReadChannel.readAvailable suspends while consuming the request body.
private suspend fun readBoundedFilePart(
    part: PartData.FileItem,
    remaining: Long,
): ByteArray? {
    val channel = part.provider()
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(UPLOAD_READ_CHUNK_SIZE)
    var totalRead = 0L

    while (true) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read == -1) break

        totalRead += read
        if (totalRead > remaining) return null

        buffer.write(chunk, 0, read)
    }

    return buffer.toByteArray()
}
