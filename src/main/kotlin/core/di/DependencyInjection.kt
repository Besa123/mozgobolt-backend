package com.mozgobolt.core.di

import com.auth0.jwt.JWTVerifier
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.mozgobolt.core.data.email.LoggingEmailService
import com.mozgobolt.core.data.email.ResendEmailService
import com.mozgobolt.core.data.email.ResilientEmailService
import com.mozgobolt.core.data.idempotency.ExposedIdempotencyStore
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
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.domain.security.VirusScanner
import com.mozgobolt.core.domain.validation.EmailValidator
import com.mozgobolt.core.domain.validation.PasswordValidator
import com.mozgobolt.core.modules.AppConfig
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

fun Application.configureDependencyInjection() {
    val appConfig: AppConfig = property("app")
    val runtimeEnvironment = environment.config.propertyOrNull("ktor.environment")?.getString() ?: "local"

    dependencies {
        provide<AppConfig> { appConfig }

        provide<DataSource> {
            createHikariDataSource(
                dbUrl = appConfig.database.url,
                dbUser = appConfig.database.user,
                dbPassword = appConfig.database.password,
                poolSize = appConfig.database.poolSize,
            )
        }

        provide(::createDatabase)
        provide<TransactionalRunner>(::ExposedTransactionalRunner)
        provide<IdempotencyStore>(::ExposedIdempotencyStore)
        provide<PasswordValidator>(::StandardPasswordValidator)
        provide<EmailValidator>(::StandardEmailValidator)

        provide<PasswordService> {
            PasswordServiceImpl(pepper = appConfig.security.passwordPepper)
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

        provide<EmailService> {
            val baseService =
                when {
                    appConfig.email.resendApiKey.isNotBlank() -> ResendEmailService(appConfig) as EmailService
                    runtimeEnvironment == PRODUCTION_ENVIRONMENT ->
                        error(
                            "RESEND_API_KEY must be set in production — refusing to fall back to " +
                                "LoggingEmailService, which logs raw tokens.",
                        )
                    else -> LoggingEmailService(appConfig) as EmailService
                }

            ResilientEmailService(baseService)
        }

        provide<ImageStorage> {
            if (appConfig.objectStorage.enabled) {
                S3ImageStorage(S3ImageStorage.buildClient(appConfig.objectStorage), appConfig.objectStorage.bucket)
            } else {
                LocalDiskImageStorage(appConfig.media.localStorageDirectory)
            }
        }
        provide<ImageSanitizer> { JavaImageSanitizer(appConfig.media) }

        provide<VirusScanner> {
            if (appConfig.clamAv.enabled) ClamAvVirusScanner(appConfig.clamAv) else NoOpVirusScanner()
        }

        provide<PushNotificationSender> {
            val baseService =
                when {
                    appConfig.push.enabled && appConfig.push.serviceAccountJson.isNotBlank() ->
                        FcmPushNotificationSender(
                            FirebaseFcmMessageSender(firebaseAppFor(appConfig.push.serviceAccountJson)),
                        )

                    appConfig.push.enabled && runtimeEnvironment == PRODUCTION_ENVIRONMENT ->
                        error(
                            "PUSH_ENABLED is true but FCM_SERVICE_ACCOUNT_JSON is blank in production — " +
                                "refusing to fall back to LoggingPushNotificationSender.",
                        )

                    else -> LoggingPushNotificationSender()
                }

            ResilientPushNotificationSender(baseService)
        }

        provide<MessageRelay> {
            appConfig.redis.url
                .takeIf { it.isNotBlank() }
                ?.let { RedisMessageRelay(it) }
                ?: NoOpMessageRelay()
        }
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
private fun firebaseAppFor(serviceAccountJson: String): FirebaseApp {
    val existing = FirebaseApp.getApps().firstOrNull()
    if (existing != null) return existing

    val credentials = GoogleCredentials.fromStream(serviceAccountJson.byteInputStream())
    val options = FirebaseOptions.builder().setCredentials(credentials).build()
    return FirebaseApp.initializeApp(options)
}
