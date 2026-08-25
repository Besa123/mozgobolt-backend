package com.besa.shelflife.feature.user.routing

import com.besa.shelflife.core.data.idempotency.IdempotencyStore
import com.besa.shelflife.core.data.idempotency.IdempotentResult
import com.besa.shelflife.core.data.idempotency.idempotent
import com.besa.shelflife.core.data.idempotency.idempotentResult
import com.besa.shelflife.core.domain.security.AuthConstants
import com.besa.shelflife.core.modules.plugin.*
import com.besa.shelflife.core.routing.dto.response.ErrorResponse
import com.besa.shelflife.core.utility.functions.protectedApi
import com.besa.shelflife.core.utility.functions.publicRateLimitedApi
import com.besa.shelflife.feature.user.domain.UserService
import com.besa.shelflife.feature.user.domain.model.LoginError
import com.besa.shelflife.feature.user.domain.model.RefreshError
import com.besa.shelflife.feature.user.domain.model.RegisterError
import com.besa.shelflife.feature.user.domain.model.VerifyEmailError
import com.besa.shelflife.feature.user.routing.dto.request.LoginRequestDto
import com.besa.shelflife.feature.user.routing.dto.request.LogoutRequestDto
import com.besa.shelflife.feature.user.routing.dto.request.RefreshRequestDto
import com.besa.shelflife.feature.user.routing.dto.request.UserCreationRequestDto
import com.besa.shelflife.feature.user.routing.dto.response.SignInResponseDto
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(
    userService: UserService,
    idempotencyStore: IdempotencyStore,
) {
    authPublicRoutes(userService, idempotencyStore)
    authProtectedRoutes(userService)
}

private fun Route.authPublicRoutes(
    userService: UserService,
    idempotencyStore: IdempotencyStore,
) {
    publicRateLimitedApi(limitName = AUTH_LIMIT) {
        route("/auth") {
            validatedPost<UserCreationRequestDto>("/register", BodyLimit.TINY, RequestTimeout.FAST) { request ->
                idempotent(idempotencyStore, requestFingerprint = request.email) {
                    userService
                        .createUser(
                            password = request.password,
                            email = request.email,
                            name = request.name,
                        ).fold(
                            onError = { registerError ->
                                val status =
                                    when (registerError) {
                                        RegisterError.ALREADY_EXISTS -> HttpStatusCode.Conflict
                                        RegisterError.WEAK_PASSWORD -> HttpStatusCode.BadRequest
                                        RegisterError.INVALID_EMAIL -> HttpStatusCode.BadRequest
                                    }
                                idempotentResult(status, ErrorResponse(error = registerError.name))
                            },
                            onSuccess = {
                                IdempotentResult(HttpStatusCode.Created, "")
                            },
                        )
                }
            }

            validatedPost<LoginRequestDto>("/login", BodyLimit.TINY, RequestTimeout.FAST) { request ->
                userService
                    .signInUser(
                        password = request.password,
                        email = request.email,
                    ).fold(
                        onSuccess = { authResponse ->
                            call.respond(
                                HttpStatusCode.OK,
                                SignInResponseDto(
                                    accessToken = authResponse.accessToken,
                                    refreshToken = authResponse.refreshToken,
                                ),
                            )
                        },
                        onError = { error ->
                            when (error) {
                                LoginError.ACCOUNT_LOCKED ->
                                    call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        ErrorResponse(error = "ACCOUNT_LOCKED")
                                    )

                                LoginError.INVALID_CREDENTIALS ->
                                    call.respond(HttpStatusCode.Unauthorized)
                            }
                        },
                    )
            }

            validatedPost<RefreshRequestDto>("/refresh", BodyLimit.SMALL, RequestTimeout.FAST) { request ->
                userService
                    .refreshToken(
                        oldRefreshToken = request.refreshToken,
                    ).fold(
                        onSuccess = { authResponse ->
                            call.respond(
                                HttpStatusCode.OK,
                                SignInResponseDto(
                                    accessToken = authResponse.accessToken,
                                    refreshToken = authResponse.refreshToken,
                                ),
                            )
                        },
                        onError = { error ->
                            when (error) {
                                RefreshError.TOKEN_REUSE_DETECTED ->
                                    call.respond(
                                        HttpStatusCode.Unauthorized,
                                        ErrorResponse(error = "TOKEN_REUSE_DETECTED")
                                    )

                                RefreshError.INVALID_CREDENTIALS ->
                                    call.respond(HttpStatusCode.Unauthorized)
                            }
                        },
                    )
            }

            get("/verify-email") {
                val token =
                    call.parameters["token"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "MISSING_TOKEN"))

                userService.verifyEmail(token).fold(
                    onSuccess = { call.respond(HttpStatusCode.OK) },
                    onError = { error ->
                        val status =
                            when (error) {
                                VerifyEmailError.INVALID_TOKEN -> HttpStatusCode.BadRequest
                                VerifyEmailError.EXPIRED_TOKEN -> HttpStatusCode.Gone
                                VerifyEmailError.ALREADY_VERIFIED -> HttpStatusCode.Conflict
                            }
                        call.respond(status, ErrorResponse(error = error.name))
                    },
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
                val principal =
                    call.principal<JWTPrincipal>()
                        ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)

                val userId = principal.payload.getClaim(AuthConstants.CLAIM_USER_ID).asInt()
                userService.logoutAllSessions(userId)
                call.respond(HttpStatusCode.OK)
            }

            limitedPost("/resend-verification", timeout = RequestTimeout.FAST) {
                val principal =
                    call.principal<JWTPrincipal>()
                        ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)

                val userId = principal.payload.getClaim(AuthConstants.CLAIM_USER_ID).asInt()

                userService.resendVerificationEmail(userId).fold(
                    onSuccess = { call.respond(HttpStatusCode.OK) },
                    onError = { error ->
                        val status =
                            when (error) {
                                VerifyEmailError.ALREADY_VERIFIED -> HttpStatusCode.Conflict
                                VerifyEmailError.INVALID_TOKEN -> HttpStatusCode.InternalServerError
                                VerifyEmailError.EXPIRED_TOKEN -> HttpStatusCode.InternalServerError
                            }
                        call.respond(status, ErrorResponse(error = error.name))
                    },
                )
            }
        }
    }
}
