package com.besa.boardShare.core.di

import com.besa.boardShare.core.data.security.JwtTokenManager
import com.besa.boardShare.core.data.security.PasswordServiceImpl
import com.besa.boardShare.core.data.validator.StandardPasswordValidator
import com.besa.boardShare.core.database.DatabaseFactory.createDatabase
import com.besa.boardShare.core.database.DatabaseFactory.createHikariDataSource
import com.besa.boardShare.core.domain.security.AuthConstants
import com.besa.boardShare.core.domain.security.PasswordConstants.PASSWORD_PEPPER
import com.besa.boardShare.core.domain.security.PasswordService
import com.besa.boardShare.core.domain.security.TokenManager
import com.besa.boardShare.core.domain.validation.PasswordValidator
import com.besa.boardShare.core.utility.module.dotEnv.DotEnv
import com.besa.boardShare.feature.user.di.configureAuthDependencyInjection
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import javax.sql.DataSource

fun Application.configureDependencyInjection() {
    dependencies {
        provide<DataSource> {
            val dbUrl = DotEnv.INSTANCE.get("DB_URL") ?: error("Missing DB_URL")
            val dbUser = DotEnv.INSTANCE.get("DB_USER") ?: error("Missing DB_USER")
            val dbPassword = DotEnv.INSTANCE.get("DB_PASSWORD") ?: error("Missing DB_PASSWORD")

            createHikariDataSource(
                dbUrl = dbUrl,
                dbUser = dbUser,
                dbPassword = dbPassword
            )
        }

        provide(::createDatabase)
        provide<PasswordValidator>(::StandardPasswordValidator)

        provide<PasswordService> {
            val pepper = DotEnv.INSTANCE.get(PASSWORD_PEPPER) ?: error("Password pepper is missing")

            PasswordServiceImpl(
                pepper = pepper,
            )
        }

        provide<TokenManager> {
            val dotenv = DotEnv.INSTANCE

            val secret =
                dotenv.get(AuthConstants.ENV_JWT_SECRET) ?: error("Hiányzó ${AuthConstants.ENV_JWT_SECRET}")
            val issuer =
                dotenv.get(AuthConstants.ENV_JWT_ISSUER) ?: error("Hiányzó ${AuthConstants.ENV_JWT_ISSUER}")
            val audience =
                dotenv.get(AuthConstants.ENV_JWT_AUDIENCE) ?: error("Hiányzó ${AuthConstants.ENV_JWT_AUDIENCE}")

            JwtTokenManager(
                secret = secret,
                issuer = issuer,
                audience = audience
            )
        }
    }

    configureAuthDependencyInjection()
}