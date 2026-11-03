/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.di

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.domain.security.AccessTokenSessionValidator
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.user.data.repository.UserEmailOutboxRepositoryI
import com.mozgobolt.feature.user.data.repository.UserRepositoryI
import com.mozgobolt.feature.user.data.security.UserEmailTokenCipherI
import com.mozgobolt.feature.user.domain.EmailTokenCipher
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.UserSessionService
import com.mozgobolt.feature.user.service.AccessTokenSessionValidatorI
import com.mozgobolt.feature.user.service.UserEmailOutboxWorker
import com.mozgobolt.feature.user.service.UserSecurityDataRetentionPurger
import com.mozgobolt.feature.user.service.UserServiceI
import com.mozgobolt.feature.user.service.UserSessionServiceI
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentSessionTerminator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}
private const val PRODUCTION_ENVIRONMENT = "production"

fun Application.configureAuthDependencyInjection() {
    dependencies {
        provide<UserRepository> {
            UserRepositoryI(refreshTokenExpiration = resolve<AppConfig>().jwt.refreshTokenExpiration.minutes)
        }
        provide<AccessTokenSessionValidator> {
            AccessTokenSessionValidatorI(
                userRepository = resolve<UserRepository>(),
                tx = resolve<TransactionalRunner>(),
            )
        }
        provide<EmailTokenCipher> { UserEmailTokenCipherI(resolve<AppConfig>().security.passwordPepper) }
        provide<UserEmailOutboxRepository> { UserEmailOutboxRepositoryI(resolve<EmailTokenCipher>()) }
        provide<UserSessionService> {
            UserSessionServiceI(
                userRepository = resolve<UserRepository>(),
                passwordService = resolve(),
                vehicleAssignmentSessionTerminator = resolve<VehicleAssignmentSessionTerminator>(),
                tx = resolve<TransactionalRunner>(),
            )
        }
        provide<UserEmailOutboxWorker> {
            UserEmailOutboxWorker(
                repository = resolve<UserEmailOutboxRepository>(),
                tokenCipher = resolve<EmailTokenCipher>(),
                emailService = resolve<EmailService>(),
                tx = resolve<TransactionalRunner>(),
                backgroundScope = resolve<ApplicationBackgroundScope>(),
            )
        }
        provide<UserSecurityDataRetentionPurger> {
            UserSecurityDataRetentionPurger(
                userRepository = resolve<UserRepository>(),
                emailOutboxRepository = resolve<UserEmailOutboxRepository>(),
                tx = resolve<TransactionalRunner>(),
                backgroundScope = resolve<ApplicationBackgroundScope>(),
            )
        }
        provide<UserService> {
            UserServiceI(
                userRepository = resolve<UserRepository>(),
                passwordService = resolve(),
                tokenManager = resolve(),
                passwordValidator = resolve(),
                emailValidator = resolve(),
                emailOutboxRepository = resolve(),
                appConfig = resolve<AppConfig>(),
                tx = resolve<TransactionalRunner>(),
            )
        }
    }
}

fun Application.configureUserSecurityDataRetentionPurger() {
    val purger: UserSecurityDataRetentionPurger by dependencies
    purger.start()
}

fun Application.configureUserEmailOutboxWorker() {
    val appConfig: AppConfig by dependencies
    val runtimeEnvironment = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "local"
    if (appConfig.email.resendApiKey.isBlank()) {
        check(runtimeEnvironment != PRODUCTION_ENVIRONMENT) {
            "RESEND_API_KEY must be set in production to deliver queued user emails"
        }
        logger.info { "Email outbox worker is idle because RESEND_API_KEY is not configured" }
        return
    }

    val worker: UserEmailOutboxWorker by dependencies
    worker.start()
}
