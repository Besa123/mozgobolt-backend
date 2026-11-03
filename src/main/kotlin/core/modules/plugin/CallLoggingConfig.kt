/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.domain.security.AuthConstants.CLAIM_USER_ID
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO

        filter { call ->
            val path = call.request.path()
            !path.startsWith("/favicon")
        }

        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val duration = call.processingTimeMillis()
            val size = call.response.headers["Content-Length"] ?: "?"
            "$status | $method $path | ${duration}ms | ${size}B"
        }

        callIdMdc("requestId")

        mdc("userId") { call ->
            call.principal<JWTPrincipal>()?.let { principal ->
                val userId = principal.payload.getClaim(CLAIM_USER_ID).asInt()
                userId?.toString()
            }
        }

        mdc("method") { call ->
            call.request.httpMethod.value
        }

        mdc("path") { call ->
            call.request.path()
        }
    }
}
