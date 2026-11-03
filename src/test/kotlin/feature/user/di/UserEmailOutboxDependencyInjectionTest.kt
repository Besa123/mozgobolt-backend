/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.di

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.email.LoggingEmailService
import com.mozgobolt.core.data.security.PasswordServiceImpl
import com.mozgobolt.core.data.validator.StandardEmailValidator
import com.mozgobolt.core.data.validator.StandardPasswordValidator
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.domain.security.PasswordBreachChecker
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.validation.EmailValidator
import com.mozgobolt.core.domain.validation.PasswordValidator
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.user.data.repository.UserEmailOutboxRepositoryI
import com.mozgobolt.feature.user.domain.UserEmailOutboxRepository
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.domain.UserSessionService
import com.mozgobolt.feature.user.service.FakeVehicleAssignmentSessionTerminator
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import com.mozgobolt.feature.user.service.UserEmailOutboxWorker
import com.mozgobolt.feature.user.service.UserServiceI
import com.mozgobolt.feature.user.service.UserSessionServiceI
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentSessionTerminator
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class UserEmailOutboxDependencyInjectionTest {
    @Test
    fun `auth dependencies resolve the transactional outbox and leave local delivery idle`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                dependencies {
                    provide<AppConfig> { testAppConfig() }
                    provide<ApplicationBackgroundScope> { ApplicationBackgroundScope() }
                    provide<TransactionalRunner> { NoopTransactionalRunner() }
                    provide<PasswordService> { PasswordServiceImpl("outbox-di-test-pepper") }
                    provide<PasswordBreachChecker> { PasswordBreachChecker { AppResult.Success(false) } }
                    provide<PasswordValidator> { StandardPasswordValidator(resolve()) }
                    provide<EmailValidator> { StandardEmailValidator() }
                    provide<EmailService> { LoggingEmailService() }
                    provide<VehicleAssignmentSessionTerminator> { FakeVehicleAssignmentSessionTerminator() }
                }

                configureAuthDependencyInjection()

                assertIs<UserServiceI>(dependencies.resolve<UserService>())
                assertIs<UserSessionServiceI>(dependencies.resolve<UserSessionService>())
                assertIs<UserEmailOutboxRepositoryI>(dependencies.resolve<UserEmailOutboxRepository>())
                dependencies.resolve<UserEmailOutboxWorker>()
                configureUserEmailOutboxWorker()
            }
        }

    @Test
    fun `production refuses to start without a configured email provider`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                testApplication {
                    configureTestEnvironment(environmentName = "production")
                    application {
                        installTestModules()
                        dependencies {
                            provide<AppConfig> { testAppConfig() }
                        }
                        configureUserEmailOutboxWorker()
                    }
                    startApplication()
                }
            }

        assertContains(failure.message.orEmpty(), "RESEND_API_KEY")
    }

    private fun testAppConfig() =
        AppConfig(
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = "unused", issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = "outbox-di-test-pepper"),
            cors = AppConfig.Cors(),
            email = AppConfig.Email(resendApiKey = ""),
        )
}
