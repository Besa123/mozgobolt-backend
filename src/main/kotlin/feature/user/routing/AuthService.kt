package com.shelflife.feature.user.routing

import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.data.idempotency.IdempotentResult
import com.shelflife.core.data.idempotency.idempotent
import com.shelflife.core.data.idempotency.idempotentResult
import com.shelflife.core.modules.plugin.AUTH_LIMIT
import com.shelflife.core.modules.plugin.BodyLimit
import com.shelflife.core.modules.plugin.RequestTimeout
import com.shelflife.core.modules.plugin.limitedPost
import com.shelflife.core.modules.plugin.validatedPost
import com.shelflife.core.routing.dto.response.ErrorResponse
import com.shelflife.core.utility.functions.currentUserIdOrNull
import com.shelflife.core.utility.functions.protectedApi
import com.shelflife.core.utility.functions.publicRateLimitedApi
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.domain.model.LoginError
import com.shelflife.feature.user.domain.model.PasswordResetError
import com.shelflife.feature.user.domain.model.RefreshError
import com.shelflife.feature.user.domain.model.RegisterError
import com.shelflife.feature.user.domain.model.VerifyEmailError
import com.shelflife.feature.user.routing.dto.request.LoginRequestDto
import com.shelflife.feature.user.routing.dto.request.LogoutRequestDto
import com.shelflife.feature.user.routing.dto.request.PasswordResetConfirmRequestDto
import com.shelflife.feature.user.routing.dto.request.PasswordResetRequestDto
import com.shelflife.feature.user.routing.dto.request.PasswordResetValidateRequestDto
import com.shelflife.feature.user.routing.dto.request.RefreshRequestDto
import com.shelflife.feature.user.routing.dto.request.UserCreationRequestDto
import com.shelflife.feature.user.routing.dto.response.MessageResponseDto
import com.shelflife.feature.user.routing.dto.response.PasswordResetValidateResponseDto
import com.shelflife.feature.user.routing.dto.response.SignInResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.authRoutes(
    userService: UserService,
    idempotencyStore: IdempotencyStore,
) {
    authPublicRoutes(userService, idempotencyStore)
    authProtectedRoutes(userService)
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
private fun Route.authPublicRoutes(
    userService: UserService,
    idempotencyStore: IdempotencyStore,
) {
    publicRateLimitedApi(limitName = AUTH_LIMIT) {
        route("/auth") {
            validatedPost<UserCreationRequestDto>("/register", BodyLimit.TINY, RequestTimeout.FAST) { request ->
                idempotent(idempotencyStore, requestFingerprint = Json.encodeToString(request)) {
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
                                        ErrorResponse(error = "ACCOUNT_LOCKED"),
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
                                        ErrorResponse(error = "TOKEN_REUSE_DETECTED"),
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

            validatedPost<PasswordResetRequestDto>(
                "/password-reset/request",
                BodyLimit.TINY,
                RequestTimeout.FAST,
            ) { request ->
                userService.requestPasswordReset(request.email).fold(
                    onSuccess = {
                        call.respond(
                            HttpStatusCode.OK,
                            MessageResponseDto("If an account exists, a password reset link has been sent."),
                        )
                    },
                    onError = { error ->
                        val status =
                            when (error) {
                                PasswordResetError.ACCOUNT_LOCKED -> HttpStatusCode.TooManyRequests
                                else -> HttpStatusCode.BadRequest
                            }
                        call.respond(status, ErrorResponse(error = error.name))
                    },
                )
            }

            validatedPost<PasswordResetValidateRequestDto>(
                "/password-reset/validate",
                BodyLimit.SMALL,
                RequestTimeout.FAST,
            ) { request ->
                userService.validatePasswordResetToken(request.token).fold(
                    onSuccess = { email ->
                        call.respond(
                            HttpStatusCode.OK,
                            PasswordResetValidateResponseDto(valid = true, email = email),
                        )
                    },
                    onError = { error ->
                        val status =
                            when (error) {
                                PasswordResetError.EXPIRED_TOKEN -> HttpStatusCode.Gone
                                else -> HttpStatusCode.BadRequest
                            }
                        call.respond(status, ErrorResponse(error = error.name))
                    },
                )
            }

            validatedPost<PasswordResetConfirmRequestDto>(
                "/password-reset/confirm",
                BodyLimit.SMALL,
                RequestTimeout.FAST,
            ) { request ->
                userService.confirmPasswordReset(request.token, request.newPassword).fold(
                    onSuccess = {
                        call.respond(
                            HttpStatusCode.OK,
                            MessageResponseDto("Password reset successfully. You can now log in."),
                        )
                    },
                    onError = { error ->
                        val status =
                            when (error) {
                                PasswordResetError.EXPIRED_TOKEN -> HttpStatusCode.Gone
                                PasswordResetError.ACCOUNT_LOCKED -> HttpStatusCode.TooManyRequests
                                else -> HttpStatusCode.BadRequest
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
                val userId =
                    call.currentUserIdOrNull()
                        ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)

                userService.logoutAllSessions(userId)
                call.respond(HttpStatusCode.OK)
            }

            limitedPost("/resend-verification", timeout = RequestTimeout.FAST) {
                val userId =
                    call.currentUserIdOrNull()
                        ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)

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
