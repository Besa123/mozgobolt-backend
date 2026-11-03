/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.service

import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.domain.model.SyncHint
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class InMemorySyncEventHub : SyncEventHub {
    private class UserChannel {
        val flow =
            MutableSharedFlow<SyncHint>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        var subscriberCount = 0
    }

    private val channelsLock = Any()
    private val channels = mutableMapOf<Int, UserChannel>()

    internal val activeUserChannelCount: Int
        get() = synchronized(channelsLock) { channels.size }

    override fun publish(
        userId: Int,
        eventId: Long,
        originDeviceId: String?,
    ) {
        val channel = synchronized(channelsLock) { channels[userId] } ?: return
        channel.flow.tryEmit(SyncHint(eventId, originDeviceId))
    }

    override fun subscribe(userId: Int): Flow<SyncHint> =
        flow {
            val channel =
                synchronized(channelsLock) {
                    channels
                        .getOrPut(userId) { UserChannel() }
                        .also { it.subscriberCount++ }
                }

            try {
                channel.flow.collect { emit(it) }
            } finally {
                synchronized(channelsLock) {
                    channel.subscriberCount--
                    if (channel.subscriberCount == 0 && channels[userId] === channel) {
                        channels.remove(userId)
                    }
                }
            }
        }
}
