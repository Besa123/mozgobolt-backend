/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.di

import com.auth0.jwt.JWTVerifier
import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.domain.media.ImageSanitizer
import com.mozgobolt.core.domain.media.ImageStorage
import com.mozgobolt.core.domain.messaging.MessageRelay
import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.core.domain.security.PasswordBreachChecker
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.domain.security.VirusScanner
import com.mozgobolt.core.domain.validation.EmailValidator
import com.mozgobolt.core.domain.validation.PasswordValidator
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.deviceInstallation.data.repository.DeviceInstallationRepositoryI
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationRepository
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityNotificationRepositoryI
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.user.data.repository.UserRepositoryI
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicle.domain.VehicleRepository
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentRepository
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertIs

class FeatureDependencyInjectionTest {
    @Test
    fun `application dependency injection composes and resolves core and feature bindings`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "ktor.environment" to "local",
                        "app.baseUrl" to "http://localhost:8080",
                        "app.database.url" to "jdbc:postgresql://localhost:5432/unused",
                        "app.database.user" to "unused",
                        "app.database.password" to "unused",
                        "app.jwt.secret" to "di-test-secret",
                        "app.jwt.issuer" to "di-test",
                        "app.jwt.audience" to "di-test",
                        "app.security.passwordPepper" to "di-test-pepper",
                        "app.cors.allowedHosts" to "",
                        "app.email.resendApiKey" to "",
                    )
            }
            application {
                configureDependencyInjection()

                assertIs<AppConfig>(dependencies.resolve<AppConfig>())
                assertIs<ApplicationBackgroundScope>(dependencies.resolve<ApplicationBackgroundScope>())
                assertIs<PasswordBreachChecker>(dependencies.resolve<PasswordBreachChecker>())
                assertIs<PasswordService>(dependencies.resolve<PasswordService>())
                assertIs<PasswordValidator>(dependencies.resolve<PasswordValidator>())
                assertIs<EmailValidator>(dependencies.resolve<EmailValidator>())
                assertIs<TokenManager>(dependencies.resolve<TokenManager>())
                assertIs<JWTVerifier>(dependencies.resolve<JWTVerifier>())
                assertIs<EmailService>(dependencies.resolve<EmailService>())
                assertIs<ImageStorage>(dependencies.resolve<ImageStorage>())
                assertIs<ImageSanitizer>(dependencies.resolve<ImageSanitizer>())
                assertIs<VirusScanner>(dependencies.resolve<VirusScanner>())
                assertIs<PushNotificationSender>(dependencies.resolve<PushNotificationSender>())
                assertIs<MessageRelay>(dependencies.resolve<MessageRelay>())
                assertIs<UserRepositoryI>(dependencies.resolve<UserRepository>())
                assertIs<CompanyRepositoryI>(dependencies.resolve<CompanyRepository>())
                assertIs<CompanyMembershipRepositoryI>(dependencies.resolve<CompanyMembershipRepository>())
                assertIs<CompanyFavoriteRepositoryI>(dependencies.resolve<CompanyFavoriteRepository>())
                assertIs<DeviceInstallationRepositoryI>(dependencies.resolve<DeviceInstallationRepository>())
                assertIs<ProximityNotificationRepositoryI>(dependencies.resolve<ProximityNotificationRepository>())
                assertIs<SavedLocationRepositoryI>(dependencies.resolve<SavedLocationRepository>())
                assertIs<VehicleRepositoryI>(dependencies.resolve<VehicleRepository>())
                assertIs<VehicleAssignmentRepositoryI>(dependencies.resolve<VehicleAssignmentRepository>())
            }
        }
}
