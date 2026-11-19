/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.core.data.messaging.RedisMessageRelay
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLiveEvent
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val TEST_LATITUDE = 47.4979
private const val TEST_LONGITUDE = 19.0402
private val RELAY_TIMEOUT = 15.seconds

class RedisRelayVehicleLocationHubRealValkeyTest {
    @BeforeTest
    fun checkDocker() {
        skipIfNoDocker()
    }

    @Test
    fun `separate instances deliver location and offline events and resume after Valkey reconnect`() =
        runBlocking {
            val valkey = GenericContainer(DockerImageName.parse("valkey/valkey:9.2.0-alpine")).withExposedPorts(6379)
            valkey.start()

            val uri = "redis://${valkey.host}:${valkey.getMappedPort(6379)}"
            val relayA = RedisMessageRelay(uri)
            val relayB = RedisMessageRelay(uri)
            val backgroundScopeA = ApplicationBackgroundScope()
            val backgroundScopeB = ApplicationBackgroundScope()
            val cellIndexer = H3CellIndexer()
            val hubA =
                RedisRelayVehicleLocationHub(
                    delegate = InMemoryVehicleLocationHub(cellIndexer),
                    relay = relayA,
                    backgroundScope = backgroundScopeA,
                )
            val hubB =
                RedisRelayVehicleLocationHub(
                    delegate = InMemoryVehicleLocationHub(cellIndexer),
                    relay = relayB,
                    backgroundScope = backgroundScopeB,
                )
            val feed = hubB.openNearby(TEST_LATITUDE, TEST_LONGITUDE, radiusKm = 1.0)
            val receivedEvents = Channel<VehicleLiveEvent>(Channel.UNLIMITED)
            val collector = launch { feed.events.collect(receivedEvents::send) }

            try {
                relayA.start()
                relayB.start()
                hubB.start()
                hubB.awaitSubscriptionReady()
                awaitSubscriberCount(valkey, expectedCount = 1)

                val firstUpdate = locationUpdate(cellIndexer)
                assertTrue(hubA.publishIfCurrent(firstUpdate))
                assertEquals(
                    VehicleLiveEvent.Position(firstUpdate),
                    withTimeout(RELAY_TIMEOUT) { receivedEvents.receive() },
                )

                val killedConnections =
                    valkey
                        .execInContainer("valkey-cli", "CLIENT", "KILL", "TYPE", "pubsub")
                        .stdout
                        .trim()
                        .toInt()
                assertTrue(killedConnections > 0, "expected the remote Pub/Sub connection to be terminated")
                awaitSubscriberCount(valkey, expectedCount = 1)

                val updateAfterReconnect =
                    firstUpdate.copy(
                        recordedAt = firstUpdate.recordedAt.plusSeconds(1),
                        idempotencyKey = UUID.randomUUID(),
                    )
                assertTrue(hubA.publishIfCurrent(updateAfterReconnect))
                assertEquals(
                    VehicleLiveEvent.Position(updateAfterReconnect),
                    withTimeout(RELAY_TIMEOUT) { receivedEvents.receive() },
                )

                assertTrue(hubA.markOffline(firstUpdate.vehicleId, checkNotNull(firstUpdate.assignmentId)))
                assertEquals(
                    VehicleLiveEvent.Offline(firstUpdate.vehicleId),
                    withTimeout(RELAY_TIMEOUT) { receivedEvents.receive() },
                )
                assertNull(hubB.lastKnownLocation(firstUpdate.vehicleId))
            } finally {
                collector.cancel()
                feed.close()
                hubA.stop()
                hubB.stop()
                backgroundScopeA.cancel()
                backgroundScopeB.cancel()
                relayA.stop()
                relayB.stop()
                valkey.stop()
            }
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

    private fun locationUpdate(cellIndexer: H3CellIndexer) =
        VehicleLocationUpdate(
            vehicleId = 42,
            vendorUserId = 7,
            latitude = TEST_LATITUDE,
            longitude = TEST_LONGITUDE,
            recordedAt = Instant.now(),
            cellId = cellIndexer.cellFor(TEST_LATITUDE, TEST_LONGITUDE),
            sharesLocationWithCustomers = true,
            assignmentId = 9,
        )
}
