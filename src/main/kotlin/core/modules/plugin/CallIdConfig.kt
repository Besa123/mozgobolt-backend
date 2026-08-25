package com.besa.shelflife.core.modules.plugin

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import java.util.UUID

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
