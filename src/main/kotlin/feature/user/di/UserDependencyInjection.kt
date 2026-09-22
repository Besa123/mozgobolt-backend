package com.mozgobolt.feature.user.di

import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.feature.user.data.repository.UserRepositoryI
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.service.UserServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve
import kotlin.time.Duration.Companion.minutes

fun Application.configureAuthDependencyInjection() {
    dependencies {
        provide<UserRepository> {
            UserRepositoryI(refreshTokenExpiration = resolve<AppConfig>().jwt.refreshTokenExpiration.minutes)
        }
        provide<UserService>(::UserServiceI)
    }
}
