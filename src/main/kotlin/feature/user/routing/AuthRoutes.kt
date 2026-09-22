package com.mozgobolt.feature.user.routing

import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.data.idempotency.IdempotentResult
import com.mozgobolt.core.data.idempotency.idempotent
import com.mozgobolt.core.data.idempotency.idempotentResult
import com.mozgobolt.core.modules.plugin.AUTH_LIMIT
import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.limitedPost
import com.mozgobolt.core.modules.plugin.validatedPatch
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.core.utility.functions.publicRateLimitedApi
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.model.LoginError
import com.mozgobolt.feature.user.domain.model.PasswordResetError
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.user.domain.model.RegisterError
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.domain.model.VerifyEmailError
import com.mozgobolt.feature.user.routing.dto.request.LoginRequestDto
import com.mozgobolt.feature.user.routing.dto.request.LogoutRequestDto
import com.mozgobolt.feature.user.routing.dto.request.PasswordResetConfirmRequestDto
import com.mozgobolt.feature.user.routing.dto.request.PasswordResetRequestDto
import com.mozgobolt.feature.user.routing.dto.request.PasswordResetValidateRequestDto
import com.mozgobolt.feature.user.routing.dto.request.RefreshRequestDto
import com.mozgobolt.feature.user.routing.dto.request.UpdateContactInfoRequestDto
import com.mozgobolt.feature.user.routing.dto.request.UserCreationRequestDto
import com.mozgobolt.feature.user.routing.dto.request.toDomain
import com.mozgobolt.feature.user.routing.dto.response.MessageResponseDto
import com.mozgobolt.feature.user.routing.dto.response.PasswordResetValidateResponseDto
import com.mozgobolt.feature.user.routing.dto.response.SignInResponseDto
import com.mozgobolt.feature.user.routing.dto.response.toResponseDto
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
                            role = UserRole.valueOf(request.role),
                            phoneNumber = request.phoneNumber,
                            whatsappNumber = request.whatsappNumber,
                            viberNumber = request.viberNumber,
                            messengerUsername = request.messengerUsername,
                        ).fold(
                            onError = { registerError ->
                                val status =
                                    when (registerError) {
                                        RegisterError.ALREADY_EXISTS -> HttpStatusCode.Conflict
                                        RegisterError.WEAK_PASSWORD -> HttpStatusCode.BadRequest
                                        RegisterError.INVALID_EMAIL -> HttpStatusCode.BadRequest
                                        RegisterError.INVALID_PHONE_NUMBER -> HttpStatusCode.BadRequest
                                        RegisterError.INVALID_MESSENGER_USERNAME -> HttpStatusCode.BadRequest
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
                userService.requestPasswordReset(request.email)
                call.respond(
                    HttpStatusCode.OK,
                    MessageResponseDto("If an account exists, a password reset link has been sent."),
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
    protectedApi {
        validatedPatch<UpdateContactInfoRequestDto>(
            "/users/me/contact-info",
            BodyLimit.TINY,
            RequestTimeout.FAST,
        ) { request ->
            val userId = call.currentUserIdOrNull() ?: return@validatedPatch call.respond(HttpStatusCode.Unauthorized)

            userService.updateContactInfo(userId, request.toDomain()).fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it.toResponseDto()) },
                onError = { error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = error.name)) },
            )
        }

        route("/auth") {
            validatedPost<LogoutRequestDto>("/logout", BodyLimit.SMALL, RequestTimeout.FAST) { request ->
                val userId =
                    call.currentUserIdOrNull()
                        ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

                userService.logoutUser(userId = userId, refreshToken = request.refreshToken)
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
                                VerifyEmailError.INVALID_TOKEN -> HttpStatusCode.Unauthorized
                                VerifyEmailError.EXPIRED_TOKEN -> HttpStatusCode.Gone
                            }
                        call.respond(status, ErrorResponse(error = error.name))
                    },
                )
            }
        }
    }
}
