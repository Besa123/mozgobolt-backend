package com.besa.boardShare.feature.user.routing

import com.besa.boardShare.core.data.idempotency.IdempotencyStore
import com.besa.boardShare.core.data.idempotency.IdempotentResult
import com.besa.boardShare.core.data.idempotency.idempotent
import com.besa.boardShare.core.data.idempotency.idempotentResult
import com.besa.boardShare.core.domain.security.AuthConstants
import com.besa.boardShare.core.modules.plugin.*
import com.besa.boardShare.core.routing.dto.response.ErrorResponse
import com.besa.boardShare.core.utility.functions.protectedApi
import com.besa.boardShare.core.utility.functions.publicRateLimitedApi
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.domain.model.RefreshError
import com.besa.boardShare.feature.user.domain.model.RegisterError
import com.besa.boardShare.feature.user.routing.dto.request.LoginRequestDto
import com.besa.boardShare.feature.user.routing.dto.request.LogoutRequestDto
import com.besa.boardShare.feature.user.routing.dto.request.RefreshRequestDto
import com.besa.boardShare.feature.user.routing.dto.request.UserCreationRequestDto
import com.besa.boardShare.feature.user.routing.dto.response.SignInResponseDto
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(userService: UserService, idempotencyStore: IdempotencyStore) {
    authPublicRoutes(userService, idempotencyStore)
    authProtectedRoutes(userService)
}

private fun Route.authPublicRoutes(userService: UserService, idempotencyStore: IdempotencyStore) {
    publicRateLimitedApi(limitName = AUTH_LIMIT) {
        route("/auth") {
            validatedPost<UserCreationRequestDto>("/register", BodyLimit.TINY, RequestTimeout.FAST) { request ->
                idempotent(idempotencyStore, requestFingerprint = request.email) {
                    userService.createUser(
                        password = request.password,
                        email = request.email,
                        name = request.name
                    ).fold(
                        onError = { registerError ->
                            val status = when (registerError) {
                                RegisterError.ALREADY_EXISTS -> HttpStatusCode.Conflict
                                RegisterError.WEAK_PASSWORD -> HttpStatusCode.BadRequest
                                RegisterError.INVALID_EMAIL -> HttpStatusCode.BadRequest
                            }
                            idempotentResult(status, ErrorResponse(error = registerError.name))
                        },
                        onSuccess = {
                            IdempotentResult(HttpStatusCode.Created, "")
                        }
                    )
                }
            }

            validatedPost<LoginRequestDto>("/login", BodyLimit.TINY, RequestTimeout.FAST) { request ->
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

            validatedPost<RefreshRequestDto>("/refresh", BodyLimit.SMALL, RequestTimeout.FAST) { request ->
                userService.refreshToken(
                    oldRefreshToken = request.refreshToken
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
                    onError = { error ->
                        when (error) {
                            RefreshError.TOKEN_REUSE_DETECTED ->
                                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "TOKEN_REUSE_DETECTED"))

                            RefreshError.INVALID_CREDENTIALS ->
                                call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                )
            }
        }
    }
}

private fun Route.authProtectedRoutes(userService: UserService) {
    protectedApi(limitName = AUTH_LIMIT) {
        route("/auth") {
            validatedPost<LogoutRequestDto>("/logout", BodyLimit.SMALL, RequestTimeout.FAST) { request ->
                userService.logoutUser(refreshToken = request.refreshToken)
                call.respond(HttpStatusCode.OK)
            }

            limitedPost("/logout-all", timeout = RequestTimeout.FAST) {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)

                val userId = principal.payload.getClaim(AuthConstants.CLAIM_USER_ID).asInt()
                userService.logoutAllSessions(userId)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}