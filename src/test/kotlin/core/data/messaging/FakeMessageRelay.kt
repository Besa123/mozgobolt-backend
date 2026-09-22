package com.mozgobolt.core.data.messaging

import com.mozgobolt.core.domain.messaging.MessageRelay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

private const val BROADCAST_BUFFER_CAPACITY = 64

/**
 * An in-memory stand-in for a real Redis relay: every [FakeMessageRelay] instance is its own
 * isolated broker, but two decorators constructed around the *same* instance behave exactly like
 * two separate application instances sharing one real Redis — publishing on one is observable by
 * a subscriber on the other. Real Redis's actual reconnection/network behavior is only provable
 * against a real Redis, see `RedisMessageRelayRealRedisTest`.
 */
class FakeMessageRelay : MessageRelay {
    data class Published(
        val channel: String,
        val message: String,
    )

    val published = mutableListOf<Published>()
    private val flowsByChannel = ConcurrentHashMap<String, MutableSharedFlow<String>>()

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun publish(
        channel: String,
        message: String,
    ) {
        published += Published(channel, message)
        flowsByChannel[channel]?.emit(message)
    }

    override fun subscribe(channel: String): Flow<String> = flowFor(channel).asSharedFlow()

    /** `replay = 0` means a message published before a collector attaches is genuinely lost —
     * exactly like real Redis Pub/Sub. A decorator's own `start()`-launched collector attaches on
     * a different dispatcher than a test's main coroutine, so a test must wait for that attachment
     * (via [MutableSharedFlow.subscriptionCount], a real coroutines primitive — not a sleep/guess)
     * before publishing, or the message is dropped exactly as it would be against a real relay. */
    suspend fun awaitSubscriber(channel: String) {
        flowFor(channel).subscriptionCount.first { it > 0 }
    }

    private fun flowFor(channel: String) =
        flowsByChannel.computeIfAbsent(channel) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = BROADCAST_BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
}
