package com.besa.boardShare.core.di

import com.besa.boardShare.core.data.security.JwtTokenManager
import com.besa.boardShare.core.data.security.PasswordServiceImpl
import com.besa.boardShare.core.data.validator.StandardEmailValidator
import com.besa.boardShare.core.data.validator.StandardPasswordValidator
import com.besa.boardShare.core.database.DatabaseFactory.createDatabase
import com.besa.boardShare.core.database.DatabaseFactory.createHikariDataSource
import com.besa.boardShare.core.domain.security.PasswordService
import com.besa.boardShare.core.domain.security.TokenManager
import com.besa.boardShare.core.domain.validation.EmailValidator
import com.besa.boardShare.core.domain.validation.PasswordValidator
import com.besa.boardShare.core.modules.AppConfig
import com.besa.boardShare.feature.user.di.configureAuthDependencyInjection
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
    }

    configureAuthDependencyInjection()
}