/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.data.idempotency.IDEMPOTENCY_KEY_HEADER
import com.mozgobolt.core.data.messaging.RedisMessageRelay
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.routing.apiV1
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.testAccessTokenFor
import com.mozgobolt.core.testAccessTokenSessionValidator
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryDispatchPlan
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.routing.dto.request.TelemetryBatchRequestDto
import com.mozgobolt.feature.vehicleTracking.routing.dto.request.TelemetryPointDto
import com.mozgobolt.feature.vehicleTracking.routing.dto.response.VehicleLocationResponseDto
import com.mozgobolt.feature.vehicleTracking.routing.vehicleTrackingRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private const val LIVE_TEST_LATITUDE = 47.4979
private const val LIVE_TEST_LONGITUDE = 19.0402
private val RELAY_TIMEOUT = 15.seconds
private typealias TelemetryResult = AppResult<TelemetryDispatchPlan, TelemetryError>

class VehicleTrackingRelaySseRealValkeyTest {
    @Test
    fun `separate route nodes publish and receive the raw location SSE event`() =
        testApplication {
            skipIfNoDocker()
            val valkey = GenericContainer(DockerImageName.parse("valkey/valkey:9.2.0-alpine")).withExposedPorts(6379)
            valkey.start()

            val relayUri = "redis://${valkey.host}:${valkey.getMappedPort(6379)}"
            val relayA = CountingMessageRelay(RedisMessageRelay(relayUri))
            val relayB = CountingMessageRelay(RedisMessageRelay(relayUri))
            val backgroundScopeA = ApplicationBackgroundScope()
            val backgroundScopeB = ApplicationBackgroundScope()
            val delegateA = InMemoryVehicleLocationHub(H3CellIndexer())
            val delegateB = InMemoryVehicleLocationHub(H3CellIndexer())
            val hubA =
                RedisRelayVehicleLocationHub(
                    delegate = delegateA,
                    relay = relayA,
                    backgroundScope = backgroundScopeA,
                )
            val hubB =
                RedisRelayVehicleLocationHub(
                    delegate = delegateB,
                    relay = relayB,
                    backgroundScope = backgroundScopeB,
                )

            try {
                relayA.start()
                relayB.start()
                hubA.start()
                hubB.start()
                hubA.awaitSubscriptionReady()
                hubB.awaitSubscriptionReady()
                awaitSubscriberCount(valkey, expectedCount = 2)

                val publisherService = RelayPublishingVehicleTrackingService(hubA)
                configureTestEnvironment()
                application {
                    installTestModules()
                    routing {
                        route("/node-a") {
                            apiV1 {
                                vehicleTrackingRoutes(
                                    publisherService,
                                    hubA,
                                    testAccessTokenSessionValidator(),
                                    FakeIdempotencyStore(),
                                )
                            }
                        }
                        route("/node-b") {
                            apiV1 {
                                vehicleTrackingRoutes(
                                    UnusedVehicleTrackingService,
                                    hubB,
                                    testAccessTokenSessionValidator(),
                                    FakeIdempotencyStore(),
                                )
                            }
                        }
                    }
                }

                val streamingClient = createClient { install(SSE) }
                try {
                    coroutineScope {
                        val nodeAEvents = Channel<ServerSentEvent>(Channel.UNLIMITED)
                        val nodeBEvents = Channel<ServerSentEvent>(Channel.UNLIMITED)
                        val buyerToken = testAccessTokenFor(userId = 43, role = "BUYER")
                        val streams =
                            listOf(
                                launch {
                                    streamLocations(streamingClient, "/node-a", buyerToken, nodeAEvents)
                                },
                                launch {
                                    streamLocations(streamingClient, "/node-b", buyerToken, nodeBEvents)
                                },
                            )

                        try {
                            awaitNearbySubscriber(delegateA)
                            awaitNearbySubscriber(delegateB)

                            val recordedAt = Instant.now()
                            val response =
                                client.post("/node-a/api/v1/vehicle-tracking/telemetry") {
                                    bearerAuth(testAccessTokenFor(userId = 42, role = "VENDOR"))
                                    header(IDEMPOTENCY_KEY_HEADER, "two-node-live-location")
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        Json.encodeToString(
                                            TelemetryBatchRequestDto(
                                                points =
                                                    listOf(
                                                        TelemetryPointDto(
                                                            latitude = LIVE_TEST_LATITUDE,
                                                            longitude = LIVE_TEST_LONGITUDE,
                                                            recordedAt = recordedAt.toString(),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                    )
                                }
                            assertEquals(HttpStatusCode.Accepted, response.status)

                            val nodeAEvent = nodeAEvents.receiveLocationEvent()
                            val nodeBEvent = nodeBEvents.receiveLocationEvent()
                            val publishedUpdate = publisherService.publishedUpdates.single()
                            val expectedResponse =
                                VehicleLocationResponseDto(
                                    vehicleId = publishedUpdate.vehicleId,
                                    latitude = publishedUpdate.latitude,
                                    longitude = publishedUpdate.longitude,
                                    recordedAt = publishedUpdate.recordedAt.toString(),
                                )
                            assertEquals("location", nodeAEvent.event)
                            assertEquals("location", nodeBEvent.event)
                            val decodedResponse =
                                Json.decodeFromString<VehicleLocationResponseDto>(requireNotNull(nodeAEvent.data))
                            assertEquals(expectedResponse, decodedResponse)
                            assertEquals(publishedUpdate.recordedAt, Instant.parse(decodedResponse.recordedAt))
                            assertEquals(nodeAEvent.data, nodeBEvent.data)
                            assertEquals(1, relayA.publishedCount)
                            assertEquals(1, relayA.receivedCount)
                            assertEquals(0, relayB.publishedCount)
                            assertEquals(1, relayB.receivedCount)
                            assertEquals(publishedUpdate, delegateB.lastKnownLocation(publishedUpdate.vehicleId))
                        } finally {
                            streams.forEach { it.cancelAndJoin() }
                        }
                    }
                } finally {
                    streamingClient.close()
                }
            } finally {
                hubA.stop()
                hubB.stop()
                backgroundScopeA.cancel()
                backgroundScopeB.cancel()
                relayA.stop()
                relayB.stop()
                valkey.stop()
            }
        }

    private suspend fun streamLocations(
        client: HttpClient,
        nodePath: String,
        buyerToken: String,
        events: Channel<ServerSentEvent>,
    ) {
        client.sse(
            request = {
                url("http://localhost$nodePath/api/v1/vehicle-tracking/live")
                url.parameters.append("scope", "nearby")
                url.parameters.append("lat", LIVE_TEST_LATITUDE.toString())
                url.parameters.append("lon", LIVE_TEST_LONGITUDE.toString())
                url.parameters.append("radiusKm", "1.0")
                bearerAuth(buyerToken)
            },
        ) {
            incoming.collect { events.send(it) }
        }
    }

    private suspend fun Channel<ServerSentEvent>.receiveLocationEvent(): ServerSentEvent =
        withTimeout(RELAY_TIMEOUT) {
            receiveAsFlow().first { it.event == "location" }
        }

    private suspend fun awaitSubscriberCount(
        valkey: GenericContainer<*>,
        expectedCount: Int,
    ) {
        withTimeout(RELAY_TIMEOUT) {
            while (true) {
                val response =
                    valkey.execInContainer(
                        "valkey-cli",
                        "PUBSUB",
                        "NUMSUB",
                        VEHICLE_LOCATION_CHANNEL,
                    )
                val subscriberCount =
                    response
                        .stdout
                        .trim()
                        .lineSequence()
                        .last()
                        .toIntOrNull() ?: 0
                if (subscriberCount == expectedCount) return@withTimeout
                delay(50)
            }
        }
    }

    private suspend fun awaitNearbySubscriber(hub: InMemoryVehicleLocationHub) {
        withTimeout(RELAY_TIMEOUT) {
            while (hub.indexedNearbyCellCount() == 0) delay(50)
        }
    }

    private class CountingMessageRelay(
        private val delegate: MessageRelay,
    ) : MessageRelay {
        private val publications = AtomicInteger()
        private val receivedMessages = AtomicInteger()

        val publishedCount: Int get() = publications.get()
        val receivedCount: Int get() = receivedMessages.get()

        override val isCrossInstanceRelaySupported: Boolean
            get() = delegate.isCrossInstanceRelaySupported

        override fun start() {
            delegate.start()
        }

        override fun stop() {
            delegate.stop()
        }

        override suspend fun awaitSubscription(channel: String) {
            delegate.awaitSubscription(channel)
        }

        override suspend fun publish(
            channel: String,
            message: String,
        ) {
            publications.incrementAndGet()
            delegate.publish(channel, message)
        }

        override fun subscribe(channel: String): Flow<String> =
            delegate.subscribe(channel).onEach { receivedMessages.incrementAndGet() }
    }

    private class RelayPublishingVehicleTrackingService(
        private val hub: VehicleLocationHub,
    ) : VehicleTrackingService {
        private val cellIndexer = H3CellIndexer()

        var publishedUpdates: List<VehicleLocationUpdate> = emptyList()
            private set

        override suspend fun prepareTelemetry(
            vendorUserId: Int,
            points: List<TelemetryPoint>,
        ): AppResult<TelemetryDispatchPlan, TelemetryError> =
            AppResult.Success(
                TelemetryDispatchPlan(
                    vehicleId = 42,
                    companyId = 7,
                    sharesLocationWithCustomers = true,
                    updates =
                        points.map { point ->
                            VehicleLocationUpdate(
                                vehicleId = 42,
                                vendorUserId = vendorUserId,
                                latitude = point.latitude,
                                longitude = point.longitude,
                                recordedAt = point.recordedAt,
                                cellId = cellIndexer.cellFor(point.latitude, point.longitude),
                                assignmentId = 9,
                                sharesLocationWithCustomers = true,
                            )
                        },
                ),
            )

        override suspend fun publishTelemetry(plan: TelemetryDispatchPlan) {
            publishedUpdates = hub.publishBatchIfCurrent(plan.updates)
        }
    }

    private object UnusedVehicleTrackingService : VehicleTrackingService {
        override suspend fun prepareTelemetry(
            vendorUserId: Int,
            points: List<TelemetryPoint>,
        ): TelemetryResult = AppResult.Error(TelemetryError.NOT_LINKED_TO_VEHICLE)

        override suspend fun publishTelemetry(plan: TelemetryDispatchPlan) = Unit
    }
}
