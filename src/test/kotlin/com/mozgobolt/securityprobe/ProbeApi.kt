/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

private val TIMEOUT = 10.seconds

internal fun newProbeHttpClient(): HttpClient =
    HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = TIMEOUT.inWholeMilliseconds
            requestTimeoutMillis = TIMEOUT.inWholeMilliseconds
        }
        install(SSE)
    }

/** One HTTP response captured whole, so a probe can inspect status, headers, and body together. */
internal data class ProbeResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    /** Parses [body] as a JSON object field, or `null` if the body is not a JSON object or lacks it. */
    fun field(name: String): JsonElement? = runCatching { Json.parseToJsonElement(body).jsonObject[name] }.getOrNull()

    fun stringField(name: String): String? = field(name)?.jsonPrimitive?.content
}

/**
 * A raw, unopinionated HTTP client over the app's public surface: every call returns the full
 * response for the probe itself to judge, rather than this client pre-deciding success/failure the
 * way [com.mozgobolt.loadtest.LiveApi] does for the honest workload.
 */
internal class ProbeApi(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    suspend fun request(
        method: HttpMethod,
        path: String,
        accessToken: String? = null,
        rawBody: String? = null,
        jsonBody: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ProbeResponse {
        val response: HttpResponse =
            http.request {
                this.method = method
                url("$baseUrl$path")
                accessToken?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
                when {
                    jsonBody != null -> {
                        contentType(ContentType.Application.Json)
                        setBody(jsonBody)
                    }

                    rawBody != null -> {
                        setBody(rawBody)
                    }
                }
            }
        return ProbeResponse(
            status = response.status.value,
            headers = response.headers.entries().associate { (key, values) -> key to values.joinToString(", ") },
            body = response.bodyAsText(),
        )
    }

    suspend fun get(
        path: String,
        accessToken: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) = request(HttpMethod.Get, path, accessToken = accessToken, headers = headers)

    suspend fun delete(
        path: String,
        accessToken: String? = null,
    ) = request(HttpMethod.Delete, path, accessToken = accessToken)

    suspend fun postJson(
        path: String,
        json: String,
        accessToken: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) = request(HttpMethod.Post, path, accessToken = accessToken, jsonBody = json, headers = headers)

    suspend fun patchJson(
        path: String,
        json: String,
        accessToken: String? = null,
    ) = request(HttpMethod.Patch, path, accessToken = accessToken, jsonBody = json)
}
