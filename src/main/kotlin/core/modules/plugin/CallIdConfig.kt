package com.besa.boardShare.core.modules.plugin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import java.util.*

fun Application.configureCallId() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)

        generate { UUID.randomUUID().toString() }

        verify { callId ->
            runCatching { UUID.fromString(callId) }.isSuccess
        }

        replyToHeader(HttpHeaders.XRequestId)
    }
}
