package com.shelflife.feature.sync.service

import com.shelflife.feature.sync.domain.SyncEventHub
import com.shelflife.feature.sync.domain.model.SyncHint
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

class InMemorySyncEventHub : SyncEventHub {
    private val flowsByUserId = ConcurrentHashMap<Int, MutableSharedFlow<SyncHint>>()

    override fun publish(
        userId: Int,
        eventId: Long,
        originDeviceId: String?,
    ) {
        flowsByUserId[userId]?.tryEmit(SyncHint(eventId, originDeviceId))
    }

    override fun subscribe(userId: Int): Flow<SyncHint> =
        flowsByUserId
            .computeIfAbsent(userId) {
                MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }.asSharedFlow()
}
