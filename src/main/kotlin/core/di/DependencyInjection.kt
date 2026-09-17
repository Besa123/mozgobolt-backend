package com.shelflife.core.di

import com.auth0.jwt.JWTVerifier
import com.shelflife.core.data.email.LoggingEmailService
import com.shelflife.core.data.email.ResendEmailService
import com.shelflife.core.data.email.ResilientEmailService
import com.shelflife.core.data.idempotency.ExposedIdempotencyStore
import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.data.media.JavaImageSanitizer
import com.shelflife.core.data.media.LocalDiskImageStorage
import com.shelflife.core.data.media.S3ImageStorage
import com.shelflife.core.data.security.ClamAvVirusScanner
import com.shelflife.core.data.security.JwtTokenManager
import com.shelflife.core.data.security.NoOpVirusScanner
import com.shelflife.core.data.security.PasswordServiceImpl
import com.shelflife.core.data.validator.StandardEmailValidator
import com.shelflife.core.data.validator.StandardPasswordValidator
import com.shelflife.core.database.DatabaseFactory.createDatabase
import com.shelflife.core.database.DatabaseFactory.createHikariDataSource
import com.shelflife.core.database.ExposedTransactionalRunner
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.domain.media.ImageSanitizer
import com.shelflife.core.domain.media.ImageStorage
import com.shelflife.core.domain.security.PasswordService
import com.shelflife.core.domain.security.TokenManager
import com.shelflife.core.domain.security.VirusScanner
import com.shelflife.core.domain.validation.EmailValidator
import com.shelflife.core.domain.validation.PasswordValidator
import com.shelflife.core.modules.AppConfig
import com.shelflife.feature.pantryEntry.di.configurePantryEntryDependencyInjection
import com.shelflife.feature.pantryEntryImage.di.configurePantryEntryImageDependencyInjection
import com.shelflife.feature.product.di.configureProductDependencyInjection
import com.shelflife.feature.quantityUnit.di.configureQuantityUnitDependencyInjection
import com.shelflife.feature.storageLocation.di.configureStorageLocationDependencyInjection
import com.shelflife.feature.sync.di.configureSyncDependencyInjection
import com.shelflife.feature.user.di.configureAuthDependencyInjection
import io.ktor.server.application.Application
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
    }

    configureAuthDependencyInjection()
    configureSyncDependencyInjection()
    configureProductDependencyInjection()
    configureStorageLocationDependencyInjection()
    configureQuantityUnitDependencyInjection()
    configurePantryEntryImageDependencyInjection()
    configurePantryEntryDependencyInjection()
}
