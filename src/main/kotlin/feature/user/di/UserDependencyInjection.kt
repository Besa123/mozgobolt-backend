package com.besa.shelflife.feature.user.di

import com.besa.shelflife.feature.user.data.repository.UserRepositoryI
import com.besa.shelflife.feature.user.domain.UserRepository
import com.besa.shelflife.feature.user.domain.UserService
import com.besa.shelflife.feature.user.service.UserServiceI
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*

fun Application.configureAuthDependencyInjection() {
    dependencies {
        provide<UserRepository> { UserRepositoryI() }
        provide<UserService>(::UserServiceI)
    }
}
