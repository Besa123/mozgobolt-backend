package com.mozgobolt.core.data.messaging

import com.mozgobolt.core.domain.messaging.MessageRelay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The single-instance default, selected automatically whenever `REDIS_URL` isn't configured (see
 * `core.di.DependencyInjection`). Publishing is a no-op and subscribing yields nothing, because
 * there is no other instance to relay to or from — a hub decorated with this relay behaves
 * exactly like the undecorated in-memory hub underneath it.
 */
class NoOpMessageRelay : MessageRelay {
    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun publish(
        channel: String,
        message: String,
    ) = Unit

    override fun subscribe(channel: String): Flow<String> = emptyFlow()
}
