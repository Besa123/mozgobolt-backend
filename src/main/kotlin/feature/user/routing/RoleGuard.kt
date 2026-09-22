package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserRoleOrNull
import com.mozgobolt.feature.user.domain.model.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext

/**
 * Coarse-grained role gate, meant to run as the first line of a handler already inside
 * `protectedApi { }`. Reusable and centralized (not an ad-hoc per-handler check), but a call-site
 * guard rather than a `protectedApi`-style route wrapper: nothing else in this codebase intercepts
 * a bare (path-less) child route, and this keeps the JWT-claim lookup in one place either way.
 */
suspend fun RoutingContext.requireRole(role: UserRole): Boolean {
    if (call.currentUserRoleOrNull() != role.name) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(error = "FORBIDDEN"))
        return false
    }
    return true
}
