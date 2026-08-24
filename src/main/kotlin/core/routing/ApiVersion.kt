package com.besa.shelflife.core.routing

import io.ktor.server.routing.*

/**
 * Defines a versioned API route group.
 *
 * All business routes go inside a version block:
 * ```kotlin
 * routing {
 *     apiV1 {
 *         authPublicRoutes(...)
 *         boardGameRoutes(...)
 *     }
 * }
 * ```
 *
 * Produces: `/api/v1/...`
 *
 * When a breaking change is needed, add [apiV2] alongside — old clients
 * keep working on v1 while new clients migrate to v2.
 */
fun Route.apiV1(build: Route.() -> Unit) = route("/api/v1", build)
