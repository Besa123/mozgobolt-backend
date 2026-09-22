package com.mozgobolt.core.modules.plugin

import kotlin.time.Duration.Companion.seconds

/**
 * Shared tuning/conventions for every long-lived SSE route (`GET .../live` endpoints) in this
 * app, so unrelated features don't each reinvent slightly different heartbeat/reconnect timing
 * or event names for the same generic SSE lifecycle concerns.
 */
val SSE_HEARTBEAT_PERIOD = 30.seconds
val RECONNECT_WARNING_LEAD_TIME = 60.seconds

/** Sent shortly before the caller's JWT expires, so the client knows to reconnect with a fresh
 * token rather than silently losing the stream. */
const val SSE_EVENT_RECONNECT = "reconnect"
