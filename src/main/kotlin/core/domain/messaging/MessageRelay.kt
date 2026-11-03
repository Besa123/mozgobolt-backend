/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.messaging

import kotlinx.coroutines.flow.Flow

/**
 * A cross-process publish/subscribe relay: lets several instances of this application, each with
 * their own local in-memory state, stay in sync by relaying messages through a shared external
 * channel instead of talking to each other directly.
 *
 * [start] must be called before [publish]/[subscribe] do anything meaningful, and [stop] releases
 * whatever [start] opened. The relay is started before its consumers and its connection is closed
 * during application shutdown; long-lived coroutine consumers are canceled by the shared
 * [com.mozgobolt.core.utility.ApplicationBackgroundScope].
 */
interface MessageRelay {
    /** Opens the shared relay connection and begins its lifecycle. */
    fun start()

    /** Closes resources opened by [start]. */
    fun stop()

    /** Publishes [message] to the shared [channel]. */
    suspend fun publish(
        channel: String,
        message: String,
    )

    /** Every message ever published to [channel] from the moment this is first called, including
     * this same instance's own publishes — a decorator re-delivering its own message into an
     * already-updated local hub is a harmless no-op, not a bug to guard against. */
    fun subscribe(channel: String): Flow<String>
}
