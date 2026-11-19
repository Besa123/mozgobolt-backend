/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.messaging

import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.utility.functions.closeAllResources
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

// Same value as InMemoryVehicleLocationHub/InMemoryVehiclePingHub's own BROADCAST_BUFFER_CAPACITY
// (each defines its own private constant rather than sharing one, matching this codebase's
// existing pattern for that value) — kept consistent deliberately, since this buffer sits
// upstream of those same hubs in the relay-decorated case and should apply the same
// drop-oldest-under-pressure policy, not a differently-tuned one.
private const val RELAY_BUFFER_CAPACITY = 64
private val SUBSCRIPTION_ACK_TIMEOUT = 10.seconds

/**
 * Redis Pub/Sub-backed [MessageRelay]. Uses two separate connections — one dedicated to
 * subscribing, one for ordinary commands — because a connection in Redis's subscriber mode can't
 * issue commands like PUBLISH. [subscribe] can be called for more than one channel on the same
 * subscribing connection; each call layers its own filter over the connection's single shared
 * message stream rather than opening a new connection per channel.
 *
 * Reconnection is Lettuce's own job, not this class's: `ClientOptions` defaults to
 * `autoReconnect = true`, so a dropped connection is retried with backoff automatically — nothing
 * here needs to detect or handle that itself.
 */
class RedisMessageRelay(
    private val redisUri: String,
) : MessageRelay {
    private var client: RedisClient? = null
    private var publishConnection: StatefulRedisConnection<String, String>? = null
    private var subscribeConnection: StatefulRedisPubSubConnection<String, String>? = null
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
    private val subscriptionReadiness = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override val isReady: Boolean
        get() = publishConnection?.isOpen == true && subscribeConnection?.isOpen == true

    override fun start() {
        check(client == null) { "RedisMessageRelay.start() may only be called once" }
        val newClient = RedisClient.create(redisUri)
        try {
            val newPublishConnection = newClient.connect()
            val newSubscribeConnection = newClient.connectPubSub()
            client = newClient
            publishConnection = newPublishConnection
            subscribeConnection = newSubscribeConnection
        } catch (failure: RedisException) {
            newClient.shutdown()
            throw failure
        }
    }

    override fun stop() {
        val publish = publishConnection
        val subscribe = subscribeConnection
        val redisClient = client
        publishConnection = null
        subscribeConnection = null
        client = null
        subscribedChannels.clear()
        subscriptionReadiness.clear()
        closeAllResources(
            { publish?.close() },
            { subscribe?.close() },
            { redisClient?.shutdown() },
        )
    }

    // Lettuce's own first-class coroutine command API (connection.coroutines(), a genuine suspend
    // fun, not a reactive-to-suspend bridge) — the correct, modern way to issue a single ordinary
    // command like PUBLISH. Still marked @ExperimentalLettuceCoroutinesApi upstream (confirmed
    // against Lettuce's actual source, not assumed) — a Lettuce-maintained, low-risk experimental
    // marker, not a red flag about this usage; opted into deliberately, not suppressed blindly.
    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun publish(
        channel: String,
        message: String,
    ) {
        val connection = checkNotNull(publishConnection) { "RedisMessageRelay.start() must be called before publish()" }
        connection.coroutines().publish(channel, message)
    }

    // observeChannels().asFlow() (reactive-bridged), not a coroutine-native subscribe — verified
    // against Lettuce's actual coroutines source: no RedisPubSubCoroutinesCommands exists anywhere
    // in that module, so this is the only correct way to consume Pub/Sub with Lettuce in Kotlin,
    // not a gap. The explicit .buffer(..., DROP_OLDEST) below is the one real addition: Redis
    // Pub/Sub has no backpressure at the wire level (the server keeps pushing regardless of
    // consumer speed), and kotlinx-coroutines-reactive's own asFlow() docs recommend an explicit
    // buffer rather than relying on its implicit default for exactly this reason.
    override fun subscribe(channel: String): Flow<String> {
        val connection =
            checkNotNull(subscribeConnection) { "RedisMessageRelay.start() must be called before subscribe()" }
        val commands = connection.commands(RedisPubSubReactiveCommands.factory())
        val readiness = subscriptionReadiness.computeIfAbsent(channel) { CompletableDeferred() }

        return channelFlow {
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    commands
                        .observeChannels()
                        .asFlow()
                        .filter { it.channel == channel }
                        .map { it.message }
                        .collect { send(it) }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: RedisException) {
                    readiness.completeExceptionally(failure)
                    throw failure
                }
            }

            if (subscribedChannels.add(channel)) {
                try {
                    commands.subscribe(channel).awaitFirstOrNull()
                    readiness.complete(Unit)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: RedisException) {
                    readiness.completeExceptionally(failure)
                    throw failure
                }
            } else {
                readiness.await()
            }
            awaitCancellation()
        }.buffer(capacity = RELAY_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    override suspend fun awaitSubscription(channel: String) {
        val readiness =
            checkNotNull(subscriptionReadiness[channel]) {
                "RedisMessageRelay.subscribe($channel) must be collected before awaiting its subscription"
            }
        withTimeout(SUBSCRIPTION_ACK_TIMEOUT) { readiness.await() }
    }
}
