package com.besa.boardShare.feature.user.routing

import com.besa.boardShare.core.modules.plugin.AUTH_LIMIT
import com.besa.boardShare.core.routing.dto.response.ErrorResponse
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.domain.model.RegisterError
import com.besa.boardShare.feature.user.routing.dto.request.LoginRequestDto
import com.besa.boardShare.feature.user.routing.dto.request.UserCreationRequestDto
import com.besa.boardShare.feature.user.routing.dto.response.SignInResponseDto
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
                    val request = call.receive<UserCreationRequestDto>()
                    userService.createUser(
                        password = request.password,
                        email = request.email,
                        name = request.name
                    ).fold(
                        onError = { registerError ->
                            val errorDto = ErrorResponse(error = registerError.name)

                            val status = when (registerError) {
                                RegisterError.ALREADY_EXISTS -> HttpStatusCode.Conflict
                                RegisterError.WEAK_PASSWORD -> HttpStatusCode.BadRequest
                            }

                            call.respond(status, errorDto)
                        },
                        onSuccess = {
                            call.respond(HttpStatusCode.Created)
                        }
                    )
                }

                post("/login") {
                    val request = call.receive<LoginRequestDto>()
                    userService.signInUser(
                        password = request.password,
                        email = request.email
                    ).fold(
                        onSuccess = { authResponse ->
                            call.respond(
                                HttpStatusCode.OK,
                                SignInResponseDto(
                                    accessToken = authResponse.accessToken,
                                    refreshToken = authResponse.refreshToken
                                )
                            )
                        },
                        onError = { _ ->
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                    )
                }
            }
        }
    }
}