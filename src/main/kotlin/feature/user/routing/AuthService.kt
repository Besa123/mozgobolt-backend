package com.besa.boardShare.feature.user.routing

import com.besa.boardShare.core.domain.AppResult
import com.besa.boardShare.core.modules.plugin.AUTH_LIMIT
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.domain.model.AuthError
import com.besa.boardShare.feature.user.routing.dto.UserDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.userRoutes() {
    val userService: UserService by dependencies

    routing {
        route("/auth") {
            rateLimit(RateLimitName(AUTH_LIMIT)) {
                post("/register") {
                    val request = call.receive<UserDto>()

                    when (val result = userService.createUser(
                        password = request.password,
                        email = request.email,
                        name = request.name
                    )) {
                        is AppResult.Success -> call.respond(HttpStatusCode.Created, result.data)
                        is AppResult.Error -> {
                            val status = when (result.errorType) {
                                AuthError.ALREADY_EXISTS -> HttpStatusCode.Conflict
                                AuthError.WEAK_PASSWORD -> HttpStatusCode.BadRequest
                                else -> HttpStatusCode.InternalServerError
                            }
                            call.respond(status, mapOf("error" to result.errorType.name))
                        }
                    }
                }
            }
        }
    }
}