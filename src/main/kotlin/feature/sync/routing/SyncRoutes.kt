package com.shelflife.feature.sync.routing

import com.shelflife.core.utility.functions.currentDeviceIdOrNull
import com.shelflife.core.utility.functions.currentUserIdOrNull
import com.shelflife.core.utility.functions.protectedApi
import com.shelflife.core.utility.functions.remainingJwtValidityOrNull
import com.shelflife.feature.sync.domain.SyncEventHub
import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val SYNC_LIVE_PATH = "/sync/live"

private val SSE_HEARTBEAT_PERIOD = 30.seconds
private val RECONNECT_WARNING_LEAD_TIME = 60.seconds

fun Route.syncRoutes(
    syncService: SyncService,
    syncEventHub: SyncEventHub,
) {
    protectedApi {
        get("/sync") {
            val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()

            val page = syncService.changesSince(userId = userId, cursor = since, limit = limit)
            call.respond(HttpStatusCode.OK, page.toResponseDto())
        }

        sse(SYNC_LIVE_PATH) {
            val userId = call.currentUserIdOrNull() ?: return@sse
            val myDeviceId = call.currentDeviceIdOrNull()
            val remainingTokenValidity = call.remainingJwtValidityOrNull() ?: return@sse

            if (remainingTokenValidity <= Duration.ZERO) return@sse

            heartbeat { period = SSE_HEARTBEAT_PERIOD }

            launch {
                delay((remainingTokenValidity - RECONNECT_WARNING_LEAD_TIME).coerceAtLeast(Duration.ZERO))
                send(ServerSentEvent(event = "reconnect"))
            }

            withTimeoutOrNull(remainingTokenValidity) {
                syncEventHub.subscribe(userId).collect { hint ->
                    if (hint.isOwnAction(myDeviceId)) return@collect
                    send(ServerSentEvent(data = hint.eventId.toString(), event = "changed"))
                }
            }
        }
    }
}
