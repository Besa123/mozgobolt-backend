package com.shelflife.feature.user.di

import com.shelflife.core.modules.AppConfig
import com.shelflife.feature.user.data.repository.UserRepositoryI
import com.shelflife.feature.user.domain.UserRepository
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.service.UserServiceI
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
