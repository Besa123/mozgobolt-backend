/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.core.utility.functions.runSuspendCatching
import com.mozgobolt.feature.user.routing.dto.request.LoginRequestDto
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.routing.dto.request.TelemetryBatchRequestDto
import com.mozgobolt.feature.vehicleTracking.routing.dto.request.TelemetryPointDto
import com.mozgobolt.feature.vehicleTracking.routing.dto.response.VehicleLocationResponseDto
import com.mozgobolt.feature.vehicleTracking.routing.dto.response.VehicleOfflineResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val CONNECT_TIMEOUT = 10.seconds
private val REQUEST_TIMEOUT = 15.seconds

// The server heartbeats every 30 seconds; a stream silent for longer than this is dead.
private val STREAM_SILENCE_TIMEOUT = 45.seconds
private const val MAX_CONNECTIONS = 4_000

private const val EVENT_LOCATION = "location"
private const val EVENT_OFFLINE = "offline"
private const val EVENT_RECONNECT = "reconnect"
private const val EVENT_ERROR = "error"

private const val API_PREFIX = "/api/v1"

internal fun newLoadHttpClient(): HttpClient =
    HttpClient(CIO) {
        expectSuccess = false
        engine {
            maxConnectionsCount = MAX_CONNECTIONS
            endpoint.maxConnectionsPerRoute = MAX_CONNECTIONS
            endpoint.connectTimeout = CONNECT_TIMEOUT.inWholeMilliseconds
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
            requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
        }
        install(SSE)
    }

/**
 * The HTTP surface the workload drives: public login, vendor telemetry, buyer live map. Request
 * timings go to [sink]; everything else is returned to the caller.
 */
internal class LiveApi(
    private val http: HttpClient,
    private val baseUrl: String,
    private val sink: ObservationSink,
    val nodeName: String = "node-a",
) {
    suspend fun health(): Int = executeAndRecordTiming("health") { url("$baseUrl/health") }

    suspend fun readiness(): Int = executeAndRecordTiming("ready") { url("$baseUrl/ready") }

    suspend fun postTelemetry(
        vendor: VendorAccount,
        idempotencyKey: String,
        points: List<TelemetryPoint>,
    ): Int =
        executeAndRecordTiming("telemetry.post", method = HttpMethod.Post) {
            url("$baseUrl$API_PREFIX/vehicle-tracking/telemetry")
            bearerAuth(vendor.credentials.accessToken)
            header("Idempotency-Key", idempotencyKey)
            header("X-Forwarded-For", forwardedFor(vendor.clientAddress))
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(points.toRequestDto()))
        }

    suspend fun login(
        credentials: Credentials,
        clientAddress: String,
        metricName: String,
    ): Int =
        executeAndRecordTiming(metricName, method = HttpMethod.Post) {
            url("$baseUrl$API_PREFIX/auth/login")
            header("X-Forwarded-For", forwardedFor(clientAddress))
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(LoginRequestDto(credentials.email, credentials.password)))
        }

    /**
     * One live-map connection as a cold flow of its events in wire order. It never throws: a failed
     * or dropped connection becomes a terminal [LiveFeedEvent.Rejected] / [LiveFeedEvent.Closed],
     * and cancelling the collector closes the connection.
     */
    fun liveFeed(
        buyer: BuyerAccount,
        center: GeoPoint,
        radiusKm: Double,
    ): Flow<LiveFeedEvent> =
        flow {
            val started = TimeSource.Monotonic.markNow()
            http.sse(
                request = {
                    url("$baseUrl$API_PREFIX/vehicle-tracking/live")
                    url.parameters.append("scope", "nearby")
                    url.parameters.append("lat", center.latitude.toString())
                    url.parameters.append("lon", center.longitude.toString())
                    url.parameters.append("radiusKm", radiusKm.toString())
                    bearerAuth(buyer.credentials.accessToken)
                    header("X-Forwarded-For", forwardedFor(buyer.clientAddress))
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = STREAM_SILENCE_TIMEOUT.inWholeMilliseconds
                    }
                },
            ) {
                emit(LiveFeedEvent.Connected(started.elapsedNow()))
                incoming.collect { emit(it.toLiveFeedEvent()) }
            }
        }.catch { failure ->
            emit(
                (failure as? SSEClientException)
                    ?.response
                    ?.status
                    ?.value
                    ?.let(LiveFeedEvent::Rejected)
                    ?: LiveFeedEvent.Closed(failure),
            )
        }.onCompletion { failure ->
            // A stream that simply ends (no exception) was closed by the server.
            if (failure == null) emit(LiveFeedEvent.Closed(cause = null))
        }

    private suspend fun executeAndRecordTiming(
        metricName: String,
        method: HttpMethod = HttpMethod.Get,
        request: HttpRequestBuilder.() -> Unit,
    ): Int {
        val started = TimeSource.Monotonic.markNow()
        val status =
            runSuspendCatching {
                http
                    .request {
                        this.method = method
                        request()
                    }.status.value
            }.getOrElse { TRANSPORT_FAILURE }
        sink.record(Observation.RequestCompleted(metricName, status, started.elapsedNow()))
        return status
    }

    companion object {
        /** Recorded instead of an HTTP status when no response arrived (timeout, refused or reset connection). */
        const val TRANSPORT_FAILURE = 0
    }
}

private fun List<TelemetryPoint>.toRequestDto() =
    TelemetryBatchRequestDto(
        points = map { TelemetryPointDto(it.latitude, it.longitude, it.recordedAt.toString()) },
    )

private fun ServerSentEvent.toLiveFeedEvent(): LiveFeedEvent =
    runCatching {
        when (event) {
            EVENT_LOCATION -> {
                val dto = Json.decodeFromString<VehicleLocationResponseDto>(requireNotNull(data))
                LiveFeedEvent.Location(
                    VehicleFix(dto.vehicleId, GeoPoint(dto.latitude, dto.longitude), Instant.parse(dto.recordedAt)),
                )
            }

            EVENT_OFFLINE -> {
                LiveFeedEvent.Offline(Json.decodeFromString<VehicleOfflineResponseDto>(requireNotNull(data)).vehicleId)
            }

            EVENT_RECONNECT -> {
                LiveFeedEvent.Reconnect
            }

            EVENT_ERROR -> {
                LiveFeedEvent.ServerError(data)
            }

            else -> {
                LiveFeedEvent.Malformed(event, "unexpected event name")
            }
        }
    }.getOrElse { failure -> LiveFeedEvent.Malformed(event, failure.javaClass.simpleName) }
