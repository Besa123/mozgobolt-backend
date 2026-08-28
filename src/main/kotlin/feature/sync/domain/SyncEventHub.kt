package com.shelflife.feature.sync.domain

import com.shelflife.feature.sync.domain.model.SyncHint
import kotlinx.coroutines.flow.Flow

interface SyncEventHub {
    fun publish(
        userId: Int,
        eventId: Long,
        originDeviceId: String? = null,
    )

    fun subscribe(userId: Int): Flow<SyncHint>
}
