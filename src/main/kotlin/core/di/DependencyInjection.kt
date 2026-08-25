package com.shelflife.core.di

import com.shelflife.core.data.email.LoggingEmailService
import com.shelflife.core.data.email.ResendEmailService
import com.shelflife.core.data.idempotency.ExposedIdempotencyStore
import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.data.security.JwtTokenManager
import com.shelflife.core.data.security.PasswordServiceImpl
import com.shelflife.core.data.validator.StandardEmailValidator
import com.shelflife.core.data.validator.StandardPasswordValidator
import com.shelflife.core.database.DatabaseFactory.createDatabase
import com.shelflife.core.database.DatabaseFactory.createHikariDataSource
import com.shelflife.core.database.ExposedTransactionalRunner
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.domain.security.PasswordService
import com.shelflife.core.domain.security.TokenManager
import com.shelflife.core.domain.validation.EmailValidator
import com.shelflife.core.domain.validation.PasswordValidator
import com.shelflife.core.modules.AppConfig
import com.shelflife.feature.user.di.configureAuthDependencyInjection
import io.ktor.server.application.Application
import io.ktor.server.config.property
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import javax.sql.DataSource

fun Application.configureDependencyInjection() {
    val appConfig: AppConfig = property("app")

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

        provide<TokenManager> {
            JwtTokenManager(
                secret = appConfig.jwt.secret,
                issuer = appConfig.jwt.issuer,
                audience = appConfig.jwt.audience,
            )
        }

        provide<EmailService> {
            if (appConfig.email.resendApiKey.isNotBlank()) {
                ResendEmailService(appConfig)
            } else {
                LoggingEmailService(appConfig)
            }
        }
    }

    configureAuthDependencyInjection()
}
