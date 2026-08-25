package com.shelflife.feature.user.di

import com.shelflife.feature.user.data.repository.UserRepositoryI
import com.shelflife.feature.user.domain.UserRepository
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.service.UserServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureAuthDependencyInjection() {
    dependencies {
        provide<UserRepository> { UserRepositoryI() }
        provide<UserService>(::UserServiceI)
    }
}
