/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.di

import com.auth0.jwt.JWTVerifier
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.mozgobolt.core.data.email.LoggingEmailService
import com.mozgobolt.core.data.email.ResendEmailService
import com.mozgobolt.core.data.email.ResilientEmailService
import com.mozgobolt.core.data.idempotency.ExposedIdempotencyStore
import com.mozgobolt.core.data.idempotency.IdempotencyRetentionPurger
import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.data.media.JavaImageSanitizer
import com.mozgobolt.core.data.media.LocalDiskImageStorage
import com.mozgobolt.core.data.media.S3ImageStorage
import com.mozgobolt.core.data.messaging.NoOpMessageRelay
import com.mozgobolt.core.data.messaging.RedisMessageRelay
import com.mozgobolt.core.data.push.FcmPushNotificationSender
import com.mozgobolt.core.data.push.FirebaseFcmMessageSender
import com.mozgobolt.core.data.push.LoggingPushNotificationSender
import com.mozgobolt.core.data.push.ResilientPushNotificationSender
import com.mozgobolt.core.data.security.ClamAvVirusScanner
import com.mozgobolt.core.data.security.HibpPasswordBreachCheckerI
import com.mozgobolt.core.data.security.JwtTokenManager
import com.mozgobolt.core.data.security.NoOpVirusScanner
import com.mozgobolt.core.data.security.PasswordServiceImpl
import com.mozgobolt.core.data.validator.StandardEmailValidator
import com.mozgobolt.core.data.validator.StandardPasswordValidator
import com.mozgobolt.core.database.DatabaseFactory.createDatabase
import com.mozgobolt.core.database.DatabaseFactory.createHikariDataSource
import com.mozgobolt.core.database.ExposedTransactionalRunner
import com.mozgobolt.core.database.TransactionalRunner
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
import com.mozgobolt.core.modules.createDispatchers
import com.mozgobolt.core.modules.validateForEnvironment
import com.mozgobolt.core.resilience.ExternalServiceResiliencePolicies
import com.mozgobolt.core.utility.ApplicationBackgroundScope
import com.mozgobolt.feature.company.di.configureCompanyDependencyInjection
import com.mozgobolt.feature.companyFavorite.di.configureCompanyFavoriteDependencyInjection
import com.mozgobolt.feature.deviceInstallation.di.configureDeviceInstallationDependencyInjection
import com.mozgobolt.feature.proximityNotification.di.configureProximityNotificationDependencyInjection
import com.mozgobolt.feature.savedLocation.di.configureSavedLocationDependencyInjection
import com.mozgobolt.feature.sync.di.configureSyncDependencyInjection
import com.mozgobolt.feature.user.di.configureAuthDependencyInjection
import com.mozgobolt.feature.vehicle.di.configureVehicleDependencyInjection
import com.mozgobolt.feature.vehicleAssignment.di.configureVehicleAssignmentDependencyInjection
import com.mozgobolt.feature.vehiclePing.di.configureVehiclePingDependencyInjection
import com.mozgobolt.feature.vehicleTracking.di.configureVehicleTrackingDependencyInjection
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.config.property
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

private const val PRODUCTION_ENVIRONMENT = "production"
private const val FIREBASE_CONNECT_TIMEOUT_MS = 1_000
private const val FIREBASE_READ_TIMEOUT_MS = 3_000
private const val FIREBASE_WRITE_TIMEOUT_MS = 3_000

fun Application.configureDependencyInjection() {
    val appConfig: AppConfig = property("app")
    val runtimeEnvironment = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "local"
    appConfig.validateForEnvironment(runtimeEnvironment)
    val runtimeDispatchers = appConfig.runtimeCapacity.createDispatchers()
    val backgroundScope = ApplicationBackgroundScope()
    val resiliencePolicies = ExternalServiceResiliencePolicies.create()

    configureExternalServiceDependencyInjection(appConfig, runtimeEnvironment, resiliencePolicies)

    dependencies {
        provide<AppConfig> { appConfig }
        provide<ApplicationBackgroundScope> { backgroundScope }

        provide<DataSource> {
            createHikariDataSource(
                dbUrl = appConfig.database.url,
                dbUser = appConfig.database.user,
                dbPassword = appConfig.database.password,
                poolSize = appConfig.runtimeCapacity.databasePoolSize,
            )
        }

        provide(::createDatabase)
        provide<TransactionalRunner> { ExposedTransactionalRunner(resolve(), runtimeDispatchers.database) }
        provide<IdempotencyStore> {
            ExposedIdempotencyStore(
                database = resolve(),
                fingerprintSecret = appConfig.security.passwordPepper,
                transactionDispatcher = runtimeDispatchers.database,
            )
        }
        provide<IdempotencyRetentionPurger> {
            IdempotencyRetentionPurger(
                store = resolve<IdempotencyStore>(),
                backgroundScope = resolve<ApplicationBackgroundScope>(),
            )
        }
        provide<PasswordValidator> { StandardPasswordValidator(resolve<PasswordBreachChecker>()) }
        provide<EmailValidator>(::StandardEmailValidator)

        provide<PasswordService> {
            PasswordServiceImpl(
                pepper = appConfig.security.passwordPepper,
                hashingDispatcher = runtimeDispatchers.passwordHashing,
            )
        }

        val jwtTokenManager =
            JwtTokenManager(
                secret = appConfig.jwt.secret,
                issuer = appConfig.jwt.issuer,
                audience = appConfig.jwt.audience,
                accessTokenExpiration = appConfig.jwt.accessTokenExpiration.minutes,
                refreshTokenExpiration = appConfig.jwt.refreshTokenExpiration.minutes,
            )
        provide<TokenManager> { jwtTokenManager }
        provide<JWTVerifier> { jwtTokenManager.accessTokenVerifier }

        provide<ImageSanitizer> { JavaImageSanitizer(appConfig.media, runtimeDispatchers.imageProcessing) }

        provide<MessageRelay> {
            appConfig.redis.url
                .takeIf { it.isNotBlank() }
                ?.let { RedisMessageRelay(it) }
                ?: NoOpMessageRelay()
        }
    }

    monitor.subscribe(ApplicationStopped) {
        backgroundScope.cancel()
    }

    configureAuthDependencyInjection()
    configureSyncDependencyInjection()
    configureCompanyDependencyInjection()
    configureCompanyFavoriteDependencyInjection()
    configureSavedLocationDependencyInjection()
    configureVehicleDependencyInjection()
    configureVehicleAssignmentDependencyInjection()
    configureDeviceInstallationDependencyInjection()
    configureProximityNotificationDependencyInjection()
    configureVehicleTrackingDependencyInjection()
    configureVehiclePingDependencyInjection()
}

private fun Application.configureExternalServiceDependencyInjection(
    appConfig: AppConfig,
    runtimeEnvironment: String,
    resiliencePolicies: ExternalServiceResiliencePolicies,
) {
    dependencies {
        provide<PasswordBreachChecker> {
            HibpPasswordBreachCheckerI(resilience = resiliencePolicies.passwordBreach)
        }

        provide<EmailService> {
            val baseService =
                when {
                    appConfig.email.resendApiKey.isNotBlank() -> {
                        ResendEmailService(appConfig) as EmailService
                    }

                    runtimeEnvironment == PRODUCTION_ENVIRONMENT -> {
                        error(
                            "RESEND_API_KEY must be set in production — refusing to use the " +
                                "development-only no-delivery email service.",
                        )
                    }

                    else -> {
                        LoggingEmailService() as EmailService
                    }
                }

            ResilientEmailService(baseService, resiliencePolicies.email)
        }

        provide<ImageStorage> {
            if (appConfig.objectStorage.enabled) {
                S3ImageStorage(
                    client = S3ImageStorage.buildClient(appConfig.objectStorage),
                    bucket = appConfig.objectStorage.bucket,
                    resilience = resiliencePolicies.s3,
                )
            } else {
                LocalDiskImageStorage(appConfig.media.localStorageDirectory)
            }
        }

        provide<VirusScanner> {
            if (appConfig.clamAv.enabled) {
                ClamAvVirusScanner(appConfig.clamAv, resiliencePolicies.clamAv)
            } else {
                NoOpVirusScanner()
            }
        }

        provide<PushNotificationSender> {
            val baseService =
                when {
                    appConfig.push.enabled && appConfig.push.serviceAccountJson.isNotBlank() -> {
                        FcmPushNotificationSender(
                            FirebaseFcmMessageSender(firebaseAppFor(appConfig.push.serviceAccountJson)),
                        )
                    }

                    appConfig.push.enabled && runtimeEnvironment == PRODUCTION_ENVIRONMENT -> {
                        error(
                            "PUSH_ENABLED is true but FCM_SERVICE_ACCOUNT_JSON is blank in production — " +
                                "refusing to fall back to LoggingPushNotificationSender.",
                        )
                    }

                    else -> {
                        LoggingPushNotificationSender()
                    }
                }

            ResilientPushNotificationSender(baseService, resiliencePolicies.push)
        }
    }
}

fun Application.configureIdempotencyRetentionPurger() {
    val purger: IdempotencyRetentionPurger by dependencies
    purger.start()
}

/**
 * Started before any hub that relays through it (see `Application.rootModule`'s call order) so a
 * hub's own [MessageRelay.subscribe] call always has an already-connected relay underneath it.
 */
fun Application.configureMessageRelay() {
    val relay: MessageRelay by dependencies
    relay.start()

    monitor.subscribe(ApplicationStopped) {
        relay.stop()
    }
}

/**
 * [FirebaseApp.initializeApp] throws if a default-named app already exists — reuse it instead of
 * assuming this provider only ever runs once, since nothing about Ktor DI's `provide { }`
 * contract guarantees that.
 */
@Suppress("CommentOverPrivateFunction")
private fun firebaseAppFor(serviceAccountJson: String): FirebaseApp {
    val existing = FirebaseApp.getApps().firstOrNull()
    if (existing != null) return existing

    val credentials = GoogleCredentials.fromStream(serviceAccountJson.byteInputStream())
    val options =
        FirebaseOptions
            .builder()
            .setCredentials(credentials)
            .setConnectTimeout(FIREBASE_CONNECT_TIMEOUT_MS)
            .setReadTimeout(FIREBASE_READ_TIMEOUT_MS)
            .setWriteTimeout(FIREBASE_WRITE_TIMEOUT_MS)
            .build()
    return FirebaseApp.initializeApp(options)
}
