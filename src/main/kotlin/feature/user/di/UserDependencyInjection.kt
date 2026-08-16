package com.besa.boardShare.feature.user.di

import com.besa.boardShare.feature.user.data.repository.UserRepositoryI
import com.besa.boardShare.feature.user.domain.UserRepository
import com.besa.boardShare.feature.user.domain.UserService
import com.besa.boardShare.feature.user.service.UserServiceI
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*

fun Application.configureAuthDependencyInjection() {
    dependencies {
        provide<UserRepository> { UserRepositoryI() }
        provide<UserService>(::UserServiceI)
    }
}