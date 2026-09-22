package com.mozgobolt.core.domain.messaging

import kotlinx.coroutines.flow.Flow

/**
 * A cross-process publish/subscribe relay: lets several instances of this application, each with
 * their own local in-memory state, stay in sync by relaying messages through a shared external
 * channel instead of talking to each other directly.
 *
 * [start] must be called before [publish]/[subscribe] do anything meaningful, and [stop] releases
 * whatever [start] opened — the same explicit lifecycle shape already used by
 * [com.mozgobolt.feature.vehicleTracking.service.VehicleLocationBatchWriter], wired the same way
 * in `Application.rootModule`, so every background process an operator needs to reason about is
 * started/stopped from the same place.
 */
interface MessageRelay {
    fun start()

    fun stop()

    suspend fun publish(
        channel: String,
        message: String,
    )

    /** Every message ever published to [channel] from the moment this is first called, including
     * this same instance's own publishes — a decorator re-delivering its own message into an
     * already-updated local hub is a harmless no-op, not a bug to guard against. */
    fun subscribe(channel: String): Flow<String>
}
