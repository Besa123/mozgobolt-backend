package com.shelflife.core.routing

import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * Groups all business routes under `/api/v1`. Breaking changes get an `apiV2`
 * added alongside — v1 keeps serving existing clients.
 */
fun Route.apiV1(build: Route.() -> Unit) = route("/api/v1", build)
