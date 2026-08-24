package com.besa.shelflife.core.di

import com.besa.shelflife.core.data.email.LoggingEmailService
import com.besa.shelflife.core.data.email.ResendEmailService
import com.besa.shelflife.core.data.idempotency.ExposedIdempotencyStore
import com.besa.shelflife.core.data.idempotency.IdempotencyStore
import com.besa.shelflife.core.data.security.JwtTokenManager
import com.besa.shelflife.core.data.security.PasswordServiceImpl
import com.besa.shelflife.core.data.validator.StandardEmailValidator
import com.besa.shelflife.core.data.validator.StandardPasswordValidator
import com.besa.shelflife.core.database.DatabaseFactory.createDatabase
import com.besa.shelflife.core.database.DatabaseFactory.createHikariDataSource
import com.besa.shelflife.core.database.ExposedTransactionalRunner
import com.besa.shelflife.core.database.TransactionalRunner
import com.besa.shelflife.core.domain.email.EmailService
import com.besa.shelflife.core.domain.security.PasswordService
import com.besa.shelflife.core.domain.security.TokenManager
import com.besa.shelflife.core.domain.validation.EmailValidator
import com.besa.shelflife.core.domain.validation.PasswordValidator
import com.besa.shelflife.core.modules.AppConfig
import com.besa.shelflife.feature.user.di.configureAuthDependencyInjection
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.di.*
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